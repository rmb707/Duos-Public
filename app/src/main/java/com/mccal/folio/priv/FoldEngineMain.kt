package com.mccal.folio.priv

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Fold8Duo: runs [FoldEngine] as the shell user WITHOUT Shizuku, for development over ADB:
 *
 *     adb shell CLASSPATH=$(pm path com.mccal.folio.dev | cut -d: -f2) \
 *         app_process /system/bin com.mccal.folio.priv.FoldEngineMain --seconds 1800
 *
 * `app_process` started from `adb shell` is uid 2000 — exactly what a Shizuku UserService is — so this proves the
 * engine (log access, device-state requests, the planner's timing) on the real phone before Shizuku is involved, and
 * it replaces the shell-script watcher and feeder with the code that will ship. It reports over the same loopback
 * protocol the app's HingeFeed already dials: `A <deg>`, `T <ms>`, `C`.
 *
 * It always ends by itself (`--seconds`), and a killed ADB client does not take a phone-side process with it — so it
 * also releases on SIGTERM, and tools/ kills it by name.
 */
object FoldEngineMain {
    @JvmStatic
    fun main(args: Array<String>) {
        fun option(name: String, default: Int) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: default
        val port = option("--port", 47291)
        val seconds = option("--seconds", 1_800).coerceIn(5, 6 * 3_600)
        val earlyLight = "--no-light" !in args
        val planner = EarlyLightPlanner(earlyLight = earlyLight, swapDelayMs = option("--swap-delay", 280).toLong(),
            earlyCoverDeg = if (earlyLight) option("--early-cover", 40) else 0)

        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun say(message: String) { println("${clock.format(Date())}  $message"); System.out.flush() }

        val clients = CopyOnWriteArrayList<OutputStream>()
        fun send(line: String) {
            val bytes = "$line\n".toByteArray()
            for (client in clients) if (runCatching { client.write(bytes); client.flush() }.isFailure) clients.remove(client)
        }
        val sink = object : FoldEngineSink {
            override fun opening(swapInMs: Int) { send("T $swapInMs"); say("opening: TENT committed, swap in ~${swapInMs} ms") }
            override fun angle(degrees: Int) = send("A $degrees")
            override fun closed() { send("C"); say("closed") }
            override fun log(message: String) = say(message)
        }
        val device = DeviceStateControl(::say)
        if ("--selftest" in args) {
            // Proves the property the whole design leans on: a request made through the platform API belongs to this
            // process, so `kill -9` here must make the SYSTEM withdraw it. Holds the request until killed or 20 s.
            val state = option("--selftest", FoldState.OPENED)
            say("selftest: pid=${android.os.Process.myPid()} requesting state $state via ${device.route}: ${device.request(state)} (now via ${device.route})")
            Thread.sleep(20_000)
            device.release(); say("selftest: released by itself"); System.exit(0)
        }
        val engine = FoldEngine(planner, device, sink)

        // Spelled out: on Android InetAddress.getLoopbackAddress() is ::1, and the app dials 127.0.0.1.
        val server = ServerSocket(port, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        thread(name = "FoldFeedAccept", isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                clients += client.getOutputStream()
                say("app connected (${clients.size})")
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { device.release() })
        thread(name = "FoldEngineLimit", isDaemon = true) { Thread.sleep(seconds * 1_000L); say("time limit reached"); engine.stop() }

        say("Fold8Duo engine: uid=${android.os.Process.myUid()} earlyLight=$earlyLight earlyCover=${if (earlyLight) option("--early-cover", 40) else 0}° " +
            "swapDelay=${option("--swap-delay", 280)}ms port=$port limit=${seconds}s")
        engine.run()
        runCatching { server.close() }
        System.exit(0)
    }
}
