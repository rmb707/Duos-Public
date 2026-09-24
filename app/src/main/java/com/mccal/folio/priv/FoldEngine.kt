package com.mccal.folio.priv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Where the engine reports to: the app over Binder (Shizuku), or a loopback socket (the ADB development harness). */
internal interface FoldEngineSink {
    fun opening(swapInMs: Int)
    fun angle(degrees: Int)
    fun closed()
    fun log(message: String)
}

/**
 * Asks the system for a device state, as whoever this process is. It only gets anywhere as the shell user
 * (`CONTROL_DEVICE_STATE` is signature-level, and `com.android.shell` holds it), i.e. under Shizuku or `adb shell`.
 *
 * Two ways in, tried in this order:
 *  1. **The platform API** (`DeviceStateManagerGlobal.requestState`, hidden, by reflection). The request then belongs
 *     to THIS process, and the system withdraws it by itself if the process dies. That matters: an OPENED request left
 *     behind on a closed phone lights only the inner panel, and the phone looks dead.
 *  2. **`cmd device_state state N`**, if the API is not reachable. It works, but makes system_server the requester, so
 *     the request outlives us until someone runs `state reset`. The planner's watchdog and [release] on shutdown are
 *     then the only things standing behind it.
 */
internal class DeviceStateControl(private val log: (String) -> Unit) {
    private val api: Api? = runCatching { Api() }.onFailure { log("device-state API unavailable (${it.javaClass.simpleName}: ${it.message}); using `cmd`") }.getOrNull()

    /** "api" or "cmd": which route requests take, for the record. */
    val route: String get() = if (api != null && !apiBroken) "api" else "cmd"
    private var apiBroken = false

    fun request(state: Int): Boolean {
        val platform = api
        if (platform != null && !apiBroken) {
            val failure = runCatching { platform.request(state) }.exceptionOrNull() ?: return true
            apiBroken = true
            log("device-state API request failed (${rootCause(failure)}); falling back to `cmd`")
        }
        return shell("cmd device_state state $state")
    }

    fun release() {
        val platform = api
        if (platform != null && !apiBroken) runCatching { platform.cancel() }
        // Always also reset the shell-made override: cheap, idempotent, and it is what would strand a closed phone.
        shell("cmd device_state state reset")
    }

    private fun shell(command: String): Boolean = runCatching {
        ProcessBuilder("sh", "-c", "$command >/dev/null 2>&1").start().waitFor() == 0
    }.getOrDefault(false)

    private fun rootCause(t: Throwable): String {
        var c = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    /** `android.hardware.devicestate.*` is not in the SDK; signatures as of Android 14–17. */
    private class Api {
        private val global: Any = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal").getMethod("getInstance").invoke(null)
            ?: error("no device_state service")
        private val requestClass = Class.forName("android.hardware.devicestate.DeviceStateRequest")
        private val callbackClass = Class.forName("android.hardware.devicestate.DeviceStateRequest\$Callback")
        private val newBuilder = requestClass.getMethod("newBuilder", Int::class.javaPrimitiveType)
        private val requestState = global.javaClass.getMethod("requestState", requestClass, Executor::class.java, callbackClass)
        private val cancelStateRequest = global.javaClass.getMethod("cancelStateRequest")

        fun request(state: Int) {
            val builder = newBuilder.invoke(null, state)!!
            val request = builder.javaClass.getMethod("build").invoke(builder)
            requestState.invoke(global, request, null, null)
        }

        fun cancel() { cancelStateRequest.invoke(global) }
    }
}

/**
 * Fold8Duo: reads what the system logs about the fold, lets [EarlyLightPlanner] decide, and carries the decisions
 * out. One thread reads `logcat`; this one waits for either a log event or the planner's next deadline.
 *
 * It must run as the shell user: reading other processes' logs needs `READ_LOGS`, and the device-state requests need
 * `CONTROL_DEVICE_STATE`. `logcat -e` does the filtering natively — the sensor HAL writes thousands of lines a second.
 */
internal class FoldEngine(
    private val planner: EarlyLightPlanner,
    private val device: DeviceStateControl,
    private val sink: FoldEngineSink,
    /** A reason to leave an opening to One UI's own timing, asked once per opening (see [HoldOffPolicy]). */
    private val holdOff: () -> String? = { null },
) {
    private val events = LinkedBlockingQueue<FoldLogEvent>()
    @Volatile private var running = false
    @Volatile private var logcat: Process? = null
    private val rotation = RotationTrace(sink::log)

    private fun nowMs() = System.nanoTime() / 1_000_000

    /** Blocks until [stop]. Never returns with a request still in force. */
    fun run() {
        running = true
        device.release()                                   // never start on top of someone else's leftover
        sink.log("engine up; device-state route: ${device.route}")
        thread(name = "FoldLogcat", isDaemon = true) { readLog() }
        try {
            while (running) {
                val deadline = planner.nextDeadline()
                val wait = if (deadline == EarlyLightPlanner.NEVER) IDLE_WAIT_MS else (deadline - nowMs()).coerceIn(0, IDLE_WAIT_MS)
                val event = events.poll(wait, TimeUnit.MILLISECONDS)
                val now = nowMs()
                when (event) {
                    is FoldLogEvent.StateCommitted -> {
                        val why = if (event.state == FoldState.TENT) holdOff() else null
                        if (why != null) sink.log("early light holds off for this opening: $why")
                        perform(planner.onStateCommitted(event.state, now, why))
                        rotation.onFoldState(when (event.state) {   // WP-59 probe P-24: reads only (RotationTrace.kt)
                            FoldState.CLOSED -> "closed"; FoldState.TENT -> "tent"; FoldState.OPENED -> "opened"; else -> "state${event.state}" })
                    }
                    is FoldLogEvent.PolicyState -> perform(planner.onPolicyState(event.state, now))
                    is FoldLogEvent.Angle -> perform(planner.onAngle(event.degrees, now))
                    null -> Unit
                }
                perform(planner.onTick(now))
            }
        } finally {
            perform(planner.onShutdown())
            device.release()
            logcat?.destroy()
            sink.log("engine down; nothing left requested")
        }
    }

    fun stop() { running = false; logcat?.destroy() }

    private fun perform(actions: List<EarlyLightPlanner.Action>) {
        for (action in actions) when (action) {
            is EarlyLightPlanner.Action.Request -> {
                val ok = device.request(action.state)
                sink.log("requested state ${action.state} via ${device.route}: ${if (ok) "ok" else "FAILED"}")
            }
            EarlyLightPlanner.Action.Release -> { device.release(); sink.log("released (${planner.lastRelease})") }
            is EarlyLightPlanner.Action.TellOpening -> sink.opening(action.swapInMs)
            is EarlyLightPlanner.Action.TellAngle -> sink.angle(action.degrees)
            EarlyLightPlanner.Action.TellClosed -> sink.closed()
        }
    }

    private fun readLog() {
        while (running) {
            val started = runCatching {
                ProcessBuilder(listOf("logcat", "-T", "1", "-v", "raw", "-e", FoldLogParser.LOGCAT_REGEX, "-s") + FoldLogParser.LOGCAT_TAGS)
                    .redirectErrorStream(true).start()
            }.getOrNull()
            if (started == null) { sink.log("cannot start logcat; retrying"); Thread.sleep(RESTART_MS); continue }
            logcat = started
            runCatching {
                BufferedReader(InputStreamReader(started.inputStream)).useLines { lines ->
                    for (line in lines) FoldLogParser.parse(line)?.let(events::offer)
                }
            }
            if (running) { sink.log("logcat ended; restarting"); Thread.sleep(RESTART_MS) }
        }
    }

    private companion object {
        const val IDLE_WAIT_MS = 1_000L
        const val RESTART_MS = 1_000L
    }
}
