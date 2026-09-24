package com.mccal.folio.priv

import android.content.Context
import android.os.IBinder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Fold8Duo: [FoldEngine] as a Shizuku UserService. Shizuku starts this class by name in its own process as the shell
 * user (uid 2000) — the same identity `adb shell` has, which is what holds `READ_LOGS` and `CONTROL_DEVICE_STATE`
 * privileges. Nothing here needs USB.
 *
 * The engine never outlives the app that asked for it: it stops when [stop] is called, when the callback's process
 * dies, and when Shizuku tears the service down. Its requests go through the platform API, so even a `kill -9` of this
 * process is cleaned up by the system about 7 s later (measured, M-10).
 */
class FoldPrivilegedService() : IFoldPrivileged.Stub() {
    /** Shizuku passes a Context when it has one; the engine does not need it. */
    @Suppress("UNUSED_PARAMETER") constructor(context: Context) : this()

    private val lock = Any()
    private var engine: FoldEngine? = null
    private var callback: IFoldPrivilegedCallback? = null
    private var death: IBinder.DeathRecipient? = null
    private var route = "?"
    private var watch: ForegroundWatch? = null
    private val mirror = DisplayMirror { message -> runCatching { callback?.onLog(message) } }
    private val mirrorLimit = java.util.Timer("FoldMirrorLimit", true)
    private var mirrorTimeout: java.util.TimerTask? = null

    override fun start(callback: IFoldPrivilegedCallback, earlyLight: Boolean, swapDelayMs: Int, earlyCoverDeg: Int) {
        synchronized(lock) {
            stopLocked()
            val sink = object : FoldEngineSink {
                override fun opening(swapInMs: Int) { runCatching { callback.onOpening(swapInMs) } }
                override fun angle(degrees: Int) { runCatching { callback.onAngle(degrees) } }
                override fun closed() { runCatching { callback.onClosed() } }
                override fun log(message: String) { runCatching { callback.onLog(message) } }
            }
            val device = DeviceStateControl(sink::log)
            route = device.route
            val planner = EarlyLightPlanner(earlyLight = earlyLight, swapDelayMs = swapDelayMs.toLong().coerceIn(0, 2_000),
                earlyCoverDeg = if (earlyLight) earlyCoverDeg.coerceIn(0, 90) else 0)
            val front = watch ?: ForegroundWatch(sink::log).also { watch = it }
            val created = FoldEngine(planner, device, sink, front::holdOff)
            engine = created
            this.callback = callback
            // The app going away — crash, force-stop, uninstall — must take the engine with it.
            death = IBinder.DeathRecipient { stop() }.also { runCatching { callback.asBinder().linkToDeath(it, 0) } }
            thread(name = "FoldEngine", isDaemon = true) { created.run() }
        }
    }

    override fun stop() { synchronized(lock) { stopLocked() } }

    override fun describe(): String =
        "uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} route=$route running=${synchronized(lock) { engine != null }} " +
            "holdOff[${watch?.describe ?: "?"}] mirror=${mirror.route}"

    override fun startMirror(surface: android.view.Surface, width: Int, height: Int): String {
        synchronized(lock) {
            // A mirror belongs to an engine with a live owner: that owner's death is what tears it down.
            if (engine == null) return "the engine is not running"
            val problem = mirror.start(surface, width, height)
            mirrorTimeout?.cancel()
            if (problem.isEmpty()) {
                // An episode is a second or two. Whatever the app does, a forgotten mirror must not keep the GPU composing.
                mirrorTimeout = object : java.util.TimerTask() { override fun run() { stopMirror() } }.also { mirrorLimit.schedule(it, MIRROR_LIMIT_MS) }
            }
            return problem
        }
    }

    override fun stopMirror() { synchronized(lock) { mirrorTimeout?.cancel(); mirrorTimeout = null; mirror.stop() } }

    override fun excludeFromCapture(layer: android.view.SurfaceControl): String = mirror.exclude(layer)

    private val snapshots by lazy { TaskSnapshots { message -> runCatching { callback?.onLog(message) } } }
    override fun taskSnapshot(packageName: String, userId: Int): android.hardware.HardwareBuffer? = snapshots.of(packageName, userId)
    override fun taskSnapshotRoute(): String = snapshots.route.let { if (it.startsWith("failed")) "$it || ${snapshots.discover()}" else it }

    private val launcher by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 33) RemoteLaunch { message -> runCatching { callback?.onLog(message) } }
        else null
    }
    override fun launch(intent: android.content.Intent, userId: Int, from: android.graphics.Rect, screenRadius: Float): String {
        if (android.os.Build.VERSION.SDK_INT < 33) return "open morph requires Android 13"
        return launcher?.launch(intent, userId, from, screenRadius) ?: "open morph unavailable"
    }

    override fun launchRoute(): String {
        if (android.os.Build.VERSION.SDK_INT < 33) return "open morph requires Android 13"
        return launcher?.route ?: "open morph unavailable"
    }

    // The platform's own Full screen for one app, switched as the shell user (AppCompatOverrides.kt).
    private val compat by lazy { AppCompatOverrides { message -> runCatching { callback?.onLog(message) } } }
    override fun setFullScreen(packageName: String, on: Boolean): String = compat.set(packageName, on)
    override fun fullScreenPackages(): String = compat.packages()

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    private fun stopLocked() {
        mirrorTimeout?.cancel(); mirrorTimeout = null
        mirror.stop()
        engine?.stop()                       // run()'s finally releases whatever it had asked for
        engine = null
        death?.let { d -> callback?.asBinder()?.let { b -> runCatching { b.unlinkToDeath(d, 0) } } }
        death = null
        callback = null
    }

    private companion object {
        const val MIRROR_LIMIT_MS = 12_000L
    }
}
