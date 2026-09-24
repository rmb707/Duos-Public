package com.mccal.folio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Fold8Duo: the hinge's REAL angle, for phones whose public sensor only reports steps.
 *
 * A Galaxy Z Fold8 tells apps 0°, 90° and 180°, and tells them late: measured on an SM-F971U1, the "90" reaches an
 * app a median 477 ms after the system itself knows the phone is opening, at a true angle anywhere from 26° to 111°.
 * The vendor sensor HAL knows the true angle the whole time and prints it to the system log from about 8° upward
 * (`lid_angle_fusion … value [state/ANGLE/…]`, ~8 Hz, ~9 ms behind). Reading that log needs the shell user, so a
 * shell-side helper (tools/early_light_proto.sh over ADB today, a Shizuku service later) forwards one line per
 * sample — `A <degrees>` — and this class turns those lines into a smooth angle the fold effect can follow.
 *
 * This class touches nothing from Android, so it runs as a plain JVM unit test.
 */
internal class HingeFeedModel {
    /** The last reported angle, or NaN before any. */
    var angle = Float.NaN; private set
    /** When [angle] arrived (the caller's clock, ms). */
    var atMs = 0L; private set
    /** Degrees per millisecond, positive while opening. */
    var velocity = 0f; private set

    private var shown = Float.NaN
    private var shownAtMs = 0L
    /** False while [velocity] is only the first-word guess: a measurement replaces a guess, it isn't averaged with it. */
    private var measured = false

    fun onSample(degrees: Float, nowMs: Long) {
        val d = degrees.coerceIn(0f, 180f)
        val gap = nowMs - atMs
        velocity = when {
            !angle.isNaN() && gap in 1..MAX_VELOCITY_GAP_MS -> {
                val v = (d - angle) / gap
                // Blend with the previous MEASURED speed: one late log line must not throw the picture forward.
                val next = if (!measured || velocity == 0f) v else velocity * .4f + v * .6f
                measured = true
                next
            }
            // The first word after a rest. The HAL only speaks once the hinge has ALREADY moved a step, so it is
            // moving right now; assume an ordinary hand until the second word says better. Without this the picture
            // sat still for the quarter-second it takes a slow open to earn that second word.
            d <= OPENING_BELOW_DEG -> { measured = false; PRIOR_SPEED }
            d >= CLOSING_ABOVE_DEG -> { measured = false; -PRIOR_SPEED }
            else -> { measured = false; 0f }
        }
        angle = d
        atMs = nowMs
    }

    /**
     * Where the hinge most likely is at [nowMs].
     *
     * Measured on an SM-F971U1: the HAL does not report on a clock, it reports each time the hinge has moved about
     * another 10° (steps of 10–13°, 40 ms apart on a fast swing and 360 ms apart on a slow one). So silence carries
     * information — the hinge has NOT yet moved another step — and the honest prediction glides on at the hinge's
     * speed while easing into "one step ahead", which it can approach but never pass. It never freezes (the old
     * time-capped glide stopped dead for ~200 ms between the words of a slow open) and it is never more than a
     * step wrong, even if the hand stops.
     */
    fun predicted(nowMs: Long): Float {
        if (angle.isNaN()) return Float.NaN
        val travel = velocity * (nowMs - atMs).coerceAtLeast(0L)
        val eased = STEP_CAP_DEG * (1f - kotlin.math.exp(-kotlin.math.abs(travel) / STEP_CAP_DEG))
        return (angle + if (travel < 0f) -eased else eased).coerceIn(0f, 180f)
    }

    /**
     * [predicted], followed rather than jumped to: each new word corrects the prediction by a few degrees, and a
     * short time-based follow turns that correction into a lean instead of a tick. Call once per frame (or more:
     * the same [nowMs] gives the same answer).
     */
    fun shown(nowMs: Long): Float {
        val target = predicted(nowMs)
        if (target.isNaN()) return Float.NaN
        val dt = nowMs - shownAtMs
        shown = if (shown.isNaN() || dt > RESYNC_MS || dt < 0) target
            else shown + (target - shown) * (1f - kotlin.math.exp(-dt / FOLLOW_MS))
        shownAtMs = nowMs
        return shown
    }

    fun reset() { angle = Float.NaN; atMs = 0L; velocity = 0f; measured = false; shown = Float.NaN; shownAtMs = 0L }

    companion object {
        /** Just under the HAL's ~10–13° reporting step: glide toward it, never past where a new word would be due. */
        const val STEP_CAP_DEG = 9.5f
        /** Two words further apart than this are separate moves, not one speed. A slow open runs at ~360 ms. */
        const val MAX_VELOCITY_GAP_MS = 700L
        /** An ordinary hand at the start of a move, °/ms (measured first steps: 11° in ~250 ms). */
        const val PRIOR_SPEED = .045f
        const val OPENING_BELOW_DEG = 30f
        const val CLOSING_ABOVE_DEG = 150f
        const val FOLLOW_MS = 45f
        const val RESYNC_MS = 500L

        /** `A <degrees>` → degrees; anything else → null. Tolerates the HAL's padded numbers ("A  47"). */
        fun parse(line: String): Float? {
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 2 || parts[0] != "A") return null
            return parts[1].toFloatOrNull()?.takeIf { it.isFinite() && it in -5f..185f }
        }
        /**
         * `T <ms>` → the system has just decided the phone is opening (its TENT commit: hall switch + fused angle, at
         * 3–13°), and the panels will swap in about that many ms (0 = unknown). Anything else → null.
         *
         * Why a signal and not the first angle word: that word's VALUE is unpredictable. Measured first words were
         * 3°, 4° and 8°; a 5° threshold ignored two openings outright, and a third was only noticed at 18°, past the
         * end of the front screen's sweep, which then snapped to dark in 50 ms. The TENT commit has no value to get
         * wrong, and the swap follows it like clockwork (391, 403, 405 ms with a 280 ms hold).
         */
        fun parseOpening(line: String): Int? {
            val parts = line.trim().split(WHITESPACE)
            if (parts.isEmpty() || parts[0] != "T") return null
            return (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 5_000)
        }

        /** `C` → the system has committed CLOSED. */
        fun isClosed(line: String) = line.trim() == "C"

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Fold8Duo: what the fold effect decided and when, for tuning it against the hinge's true angle. Development builds
 * only; read it back with `adb logcat -s FolioFoldTrace`. One line per event, and at most ten samples a second.
 */
internal object FoldTrace {
    private const val TAG = "FolioFoldTrace"
    private const val SAMPLE_EVERY_MS = 100L
    @Volatile var enabled = false
    private var lastSampleAt = 0L

    fun event(message: String) { if (enabled) Log.i(TAG, message) }

    inline fun sample(nowMs: Long, message: () -> String) {
        if (enabled && due(nowMs)) Log.i("FolioFoldTrace", message())
    }

    @PublishedApi internal fun due(nowMs: Long): Boolean {
        if (nowMs - lastSampleAt < SAMPLE_EVERY_MS) return false
        lastSampleAt = nowMs
        return true
    }
}

/**
 * Reads [HingeFeedModel] lines from the shell-side feeder over a loopback socket. Development builds only (package
 * `….dev`): any app on the phone can reach a loopback port, which is acceptable for an animation hint in a test build
 * and not for a release — the Shizuku service delivers the same samples over Binder instead.
 *
 * The app dials the feeder, not the other way round. Android firewalls a background app's sockets, loopback included,
 * so a feeder that has to find the app only gets through a second or so after every unlock (measured: connected
 * 325 ms after the panel lit, half-way through the very open it was meant to drive). Dialling out the moment Home
 * comes on screen has the angle ready before the hinge moves.
 */
internal object HingeFeed {
    const val PORT = 47291
    private const val TAG = "FolioHinge"
    private const val CONNECT_TIMEOUT_MS = 250
    private const val RETRY_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    /** The listeners the development socket is dialled for: the ones on screen. */
    private val dialling = CopyOnWriteArrayList<Listener>()
    private val model = HingeFeedModel()
    private val wake = Object()
    @Volatile private var started = false
    @Volatile private var socket: Socket? = null

    /**
     * True while a real angle can arrive — from the Shizuku service over Binder, or from the development socket. The
     * HAL is silent at rest, so recent samples can't stand in for this.
     */
    val connected get() = serviceConnected || socketConnected
    @Volatile private var serviceConnected = false
    @Volatile private var socketConnected = false

    /** The Shizuku service came or went (any thread). */
    fun serviceState(up: Boolean) = main.post {
        val before = connected
        serviceConnected = up
        if (up) model.reset()
        if (connected != before) listeners.forEach { it.onFeedChanged(connected) }
    }

    /**
     * One word of the true angle from the Shizuku service (any thread). The model is kept current even while nobody
     * is listening: Binder still delivers with Home off screen, so an open straight from sleep already has an angle.
     */
    fun serviceAngle(degrees: Float) {
        val now = SystemClock.uptimeMillis()
        main.post { deliver(degrees, now) }
    }

    /**
     * Fold8Duo: true while the listeners hear an engine word that is the tail of a close rather than the hinge rising
     * (duo/ClosingTail.kt). Judged once per word, here, so Home and the overlay can't disagree or miss the history.
     */
    var closingTail = false; private set
    private val tail = com.mccal.folio.duo.ClosingTail()

    /** One engine word, on the main thread: the model, the tail verdict, then every listener. */
    private fun deliver(degrees: Float, now: Long) {
        model.onSample(degrees, now)
        closingTail = tail.isTail(degrees, now)
        listeners.forEach { it.onHingeAngle(degrees) }
    }

    /** A signal from the Shizuku service, delivered to every listener on the main thread. */
    fun fromService(deliver: (Listener) -> Unit) { main.post { listeners.forEach(deliver) } }

    interface Listener {
        /** A new real angle (main thread). */
        fun onHingeAngle(degrees: Float)
        /** The feeder connected or went away (main thread). */
        fun onFeedChanged(connected: Boolean)
        /** The system says the phone is opening; the panels swap in about [swapInMs] (0 = unknown). Main thread. */
        fun onOpeningSignal(swapInMs: Int) = Unit
        /** The system says the phone is closed. Main thread. */
        fun onClosedSignal() = Unit
    }

    /** The angle to draw right now, or null when no feeder is connected or nothing has been reported yet. */
    fun predicted(): Float? {
        if (!connected) return null
        val value = model.shown(SystemClock.uptimeMillis())
        return if (value.isNaN()) null else value
    }

    /**
     * [devSocket] = false for a listener that lives off screen (the over-app fold overlay): Android firewalls a
     * background app's sockets, so dialling from there would only be a retry loop that never connects. Binder reaches
     * it regardless.
     */
    fun attach(context: Context, listener: Listener, devSocket: Boolean = true) {
        listeners.addIfAbsent(listener)
        FoldShizuku.ensureStarted(context)                 // the product transport; does nothing until Shizuku is running
        if (!context.packageName.endsWith(".dev")) return
        FoldTrace.enabled = true
        com.mccal.folio.duo.StandaloneProbe.ensureStarted(context)   // WP-80 probes P-28/P-29 (duo/StandaloneProbe.kt)
        if (!devSocket) return
        dialling.addIfAbsent(listener)
        if (!started) { started = true; thread(name = "FolioHingeFeed", isDaemon = true) { run() } } /* a thread name */ // english-only
        synchronized(wake) { wake.notifyAll() }
    }

    /** Off screen there is nothing to draw and the socket is about to be firewalled: hang up, redial on return. */
    fun detach(listener: Listener) {
        listeners.remove(listener)
        dialling.remove(listener)
        if (dialling.isEmpty()) runCatching { socket?.close() }
    }

    private fun run() {
        // Spelled out: on Android InetAddress.getLoopbackAddress() is the IPv6 one (::1); the feeder listens on IPv4.
        val feeder = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), PORT)
        while (true) {
            synchronized(wake) { while (dialling.isEmpty()) wake.wait() }
            val dialled = Socket()
            val ok = runCatching { dialled.connect(feeder, CONNECT_TIMEOUT_MS) }.isSuccess
            if (!ok) { runCatching { dialled.close() }; Thread.sleep(RETRY_MS); continue }
            socket = dialled
            main.post {
                val before = connected
                socketConnected = true
                if (!serviceConnected) model.reset()
                if (connected != before) listeners.forEach { it.onFeedChanged(true) }
            }
            Log.i(TAG, "hinge feed connected")
            runCatching {
                BufferedReader(InputStreamReader(dialled.getInputStream())).useLines { lines ->
                    for (line in lines) {
                        val degrees = HingeFeedModel.parse(line)
                        if (serviceConnected) continue        // the service is already saying the same things
                        if (degrees != null) {
                            val now = SystemClock.uptimeMillis()
                            main.post { deliver(degrees, now) }
                            continue
                        }
                        val swapInMs = HingeFeedModel.parseOpening(line)
                        if (swapInMs != null) { main.post { listeners.forEach { it.onOpeningSignal(swapInMs) } }; continue }
                        if (HingeFeedModel.isClosed(line)) main.post { listeners.forEach { it.onClosedSignal() } }
                    }
                }
            }
            runCatching { dialled.close() }
            socket = null
            main.post {
                val before = connected
                socketConnected = false
                if (!serviceConnected) model.reset()
                if (connected != before) listeners.forEach { it.onFeedChanged(false) }
            }
            Log.i(TAG, "hinge feed disconnected")
        }
    }
}
