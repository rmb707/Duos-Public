package com.mccal.folio

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.mccal.folio.duo.DuoFrame
import com.mccal.folio.duo.DuoPanePainter
import com.mccal.folio.duo.FoldStyle
import com.mccal.folio.duo.FoldStyles
import com.mccal.folio.duo.PaneSpring
import com.mccal.folio.stage.VeilModel
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp

/**
 * Fold8Duo: the fold effect over every app (FoldSync, SPEC §4.5–4.6).
 *
 * On Home the effect is a RenderEffect over Folio's own content, driven by [FoldTimeline] while [MainActivity] is
 * started — so with any other app in front there was nobody listening and nothing to draw on, while the Shizuku engine
 * (which is not tied to Home) went on changing screens early with nothing over the swap. This runs the very same
 * [FoldTimeline] whenever Home is NOT on screen, and draws its one number — the effect's strength — on [OverlayStage].
 * Home keeps its own renderer; the two are never active together.
 *
 * What it draws: the accepted look is `pixel × (1 − 0.9·dark) + glint` behind one soft front ([VeilModel]). Over another
 * app that is a black veil and a little light, which needs no pixels, so it works over anything — a bank, a film, the
 * lock screen. The frost that runs ahead of the dark does need pixels; when the engine can mirror the screen it is
 * added, and when it can't (no Shizuku, secure content shows black) the front simply arrives without it.
 *
 * Because the look is dark at both ends, the panels always swap under full dark: there is no picture to carry across
 * the fold, which is the hard part of every other approach to this.
 */
internal class FoldOverlay(private val service: AccessibilityService, private val stage: OverlayStage) :
    HingeFeed.Listener, SensorEventListener {

    private val main = Handler(Looper.getMainLooper())
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val sensors = service.getSystemService(SensorManager::class.java)
    private val fold = FoldTimeline(service, devSocket = false).apply { stayAwake = false }   // no FoldBridgeActivity over someone else's app
    private val painter = VeilPainter()

    private var attached = false
    private var homeStarted = 0
    private var intensity = 1f
    /** Which panel was lit the last time we looked while the screen was on; null until we have looked. */
    private var lastExpanded: Boolean? = null
    /** Set when the screen goes dark: waking on the other panel is an unfold (or fold) we did not see happen. */
    private var wentDarkOn: Boolean? = null

    private var m = 0f
    private var frameAt = 0L
    private var litFrames = 0
    private var movedAt = 0L
    private var movedM = 0f
    // Fold8Duo, iPhone Duo style (duo/DuoFold.kt): the pane painter, its spring, and the position-lock still — a copy of
    // the last mirror frame before a swap, drawn 1:1 on the other panel until it melts into the live app.
    private var style = FoldStyle.SWEEP
    private val duoPainter = DuoPanePainter()
    private val spring = PaneSpring()
    /** The inner pane's tilt comes from the real angle; this spring gives it its inertia and the settle at flat. */
    private val tiltSpring = PaneSpring()
    private var duoFrame = DuoFrame.NONE
    private var still: Bitmap? = null
    /** Which panel [still] was taken on: it is drawn only on the other one. */
    private var stillOnExpanded = false

    fun start() {
        (service.application as Application).registerActivityLifecycleCallbacks(activities)
        if (FolioForeground.visible.value) homeStarted = 1
        displays.registerDisplayListener(displayListener, main)
        if (service.packageName.endsWith(".dev")) registerDebug()
        evaluate()
    }

    fun stop() {
        (service.application as Application).unregisterActivityLifecycleCallbacks(activities)
        displays.unregisterDisplayListener(displayListener)
        debugReceiver?.let { runCatching { service.unregisterReceiver(it) } }; debugReceiver = null
        detach()
    }

    // --------------------------------------------------------------------------------- when this is the one drawing

    private val activities = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { if (activity is MainActivity) { homeStarted++; evaluate() } }
        override fun onActivityStopped(activity: Activity) { if (activity is MainActivity) { homeStarted = (homeStarted - 1).coerceAtLeast(0); evaluate() } }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            evaluate()
            if (!attached) return
            val expanded = expandedNow()
            lastExpanded = expanded
            if (expanded != fold.expanded) switchTo(expanded)
        }
    }

    private fun screenOn() = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    /** The same test Home uses for "this is the open screen", from the display itself: a service has no window. */
    private fun expandedNow(): Boolean {
        val size = stage.screenSize()
        val dpi = service.resources.configuration.densityDpi.takeIf { it > 0 } ?: return fold.expanded
        val density = dpi / 160f
        return fitsRegularHomeLayout(size.x / density, size.y / density, classScale(dpi, DisplayMetrics.DENSITY_DEVICE_STABLE))
    }

    private fun evaluate() {
        val on = screenOn()
        if (!on && wentDarkOn == null) wentDarkOn = lastExpanded
        // Nothing of ours belongs on a screen that is off or dozing, whatever scene it is (measured: a scene left up
        // while the lock screen timed out ran at the always-on display's rate, 70-90 fps with dozens of late frames).
        if (!on && stage.showing != null) stage.hide("the screen is off")
        // Started, or resumed: either way Home's own renderer is the one drawing.
        val home = homeStarted > 0 || FolioForeground.visible.value
        val want = on && !home && !SafeMode.active && FoldShizuku.overApps(service)
        if (want == attached) return
        if (want) attach() else detach()
    }

    private fun attach() {
        val state = runCatching { JSONObject(service.getSharedPreferences(SettingKeys.PREFS, 0).getString(SettingKeys.STATE, "{}") ?: "{}") }.getOrNull()
        if (state?.optBoolean("foldEffect", true) == false || reduceMotionEnabled(service)) return      // Home's own switch governs both
        intensity = (state?.optDouble("foldIntensity", 1.0) ?: 1.0).toFloat().coerceIn(.3f, 1.5f)
        style = FoldStyles.read(service)
        // WP-56: the fold's haptics over apps too — halfway, flat, closed — gated by Folio's Haptics switch.
        val haptics = state?.optBoolean("haptics", true) != false
        fold.onHalfway = { if (haptics) stage.haptic(MotionTokens.Haptics::halfway) }
        fold.onReachedFlat = { if (haptics) stage.haptic(MotionTokens.Haptics::flat) }
        fold.onReachedClosed = { if (haptics) stage.haptic(MotionTokens.Haptics::closed) }
        attached = true
        val expanded = expandedNow()
        val before = wentDarkOn
        wentDarkOn = null; lastExpanded = expanded
        fold.start()
        HingeFeed.attach(service, this, devSocket = false)            // after the timeline: it must hear each word first
        sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Woke on the other panel: the phone was opened (or shut) while dark, and what is coming up is ours to reveal.
        if (before != null && before != expanded) switchTo(expanded) else fold.expanded = expanded
    }

    private fun detach() {
        if (!attached) return
        attached = false
        fold.stop()
        HingeFeed.detach(this)
        sensors?.unregisterListener(this)
        if (stage.showing === scene) stage.hide("Home is drawing the fold effect now, or the screen is off") /* a reason for logcat */ // english-only
        m = 0f
        dropStill()
    }

    private fun switchTo(expanded: Boolean) {
        val now = SystemClock.uptimeMillis()
        fold.expanded = expanded
        fold.onDisplaySwitched(now)
        // Decided here, not on the next frame: the first frame on the new panel must already be covered.
        m = if (expanded) FoldTuning.startMOnUnfold else FoldTuning.coverStartM
        spring.snap(m)
        litFrames = 0; movedAt = now; movedM = m
        FoldTrace.event("over an app: display switched expanded=$expanded")
        kick()
    }

    /** Something happened that may have started an episode. The stage only has a surface while one is running. */
    private fun kick() {
        if (!attached || !(fold.busy || m > 0f)) return
        if (stage.showing == null) { frameAt = 0L; movedAt = SystemClock.uptimeMillis(); movedM = m }
        stage.show(scene)
    }

    override fun onHingeAngle(degrees: Float) = kick()
    override fun onFeedChanged(connected: Boolean) = kick()
    override fun onOpeningSignal(swapInMs: Int) = kick()
    override fun onClosedSignal() = kick()
    // The public sensor has its own queue per listener, so the timeline may hear this step a moment after we do.
    override fun onSensorChanged(event: SensorEvent) { main.post(::kick); main.postDelayed(::kick, 40) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------------------------------------- the scene

    private val scene = object : OverlayStage.Scene {
        override val name = "fold"
        override val maxMs = 30_000L                   // a hinge can be moved slowly; [STILL_MS] is the real guard
        override val wantsLive get() = FoldShizuku.liveFrost(service)

        override fun draw(canvas: Canvas, width: Int, height: Int, nowMs: Long, live: LiveFrame?): Boolean {
            val dt = if (frameAt == 0L) 16f else (nowMs - frameAt).toFloat().coerceIn(1f, 64f)
            frameAt = nowMs
            if (fold.waitingForPanel) {
                litFrames = if (screenOn()) litFrames + 1 else 0
                if (litFrames >= 2 || nowMs - fold.switchedAt > FoldTuning.litTimeoutMs) { fold.onPanelLit(nowMs); litFrames = 0 }
            }
            val target = fold.targetM(nowMs)
            val duo = style == FoldStyle.DUO
            // Fold8Duo: the iPhone Duo style follows through a spring (a little inertia); the sweep through a short follow.
            val next = if (duo) spring.step(target, dt) else m + (target - m) * (1f - exp(-dt / FoldTuning.followMs))
            m = if (target == 0f && next < .003f && (!duo || spring.settled(0f))) 0f else next
            if (m == 0f) spring.snap(0f)
            duoFrame = if (!duo) DuoFrame.NONE else fold.duoFrame(nowMs, m, target).let { raw ->
                if (!fold.expanded || fold.waitingForPanel || raw.idle) { tiltSpring.snap(raw.tiltDeg); raw }
                else raw.copy(tiltDeg = tiltSpring.step(raw.tiltDeg, dt))
            }
            FoldTrace.sample(nowMs) { "over an app: m=${"%.2f".format(m)} target=${"%.2f".format(target)} angle=${"%.0f".format(fold.traceAngle())} expanded=${fold.expanded} live=${live != null} duo=$duoFrame" }
            if (abs(m - movedM) > .004f) { movedM = m; movedAt = nowMs }
            // SPEC I-3: a veil that has stopped changing has lost its hinge. The timeline lets go of a resting hinge by
            // itself; this is for the day it doesn't.
            if (m > 0f && nowMs - movedAt > STILL_MS) { Log.w(OverlayStage.TAG, "fold: the veil stood still at m=$m for ${STILL_MS} ms; letting go"); m = 0f; dropStill(); return false }
            if (m <= 0f && !fold.busy) { dropStill(); return false }
            val rotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0
            val geometry = foldGeometry(rotation, null, width.toFloat(), height.toFloat())
            if (duo) {
                keepStill(live)
                val rect = stillRect(geometry, rotation)
                duoPainter.draw(canvas, width, height, duoFrame, cover = !fold.expanded, geometry = geometry, pxPerMm = pxPerMm(width),
                    intensity = intensity, live = live?.bitmap, still = if (rect != null) still else null, stillRect = rect)
                if (rect != null && duoFrame.still <= 0f) dropStill()      // melted into the live app
            } else painter.draw(canvas, width, height, (m * intensity).coerceIn(0f, 1.5f), cover = !fold.expanded,
                geometry = geometry, live = live?.bitmap)
            return true
        }

        override fun onGone(reason: String) { m = 0f; frameAt = 0L; spring.snap(0f); tiltSpring.snap(0f); duoFrame = DuoFrame.NONE; dropStill() }
    }

    // ------------------------------------------------------------------------ the iPhone Duo style's still, over apps

    /**
     * Before the opening swap, keep the last mirror frame of the cover: the picture the inner right half must carry (the
     * position lock — in the film the icons stay put as the phone opens). One copy per opening, taken as the cover starts
     * to dusk, while the mirror still shows it. Needs the mirror (live frost). Folding carries nothing: the film shows the
     * cover's own picture coming into focus.
     */
    private fun keepStill(live: LiveFrame?) {
        if (fold.expanded || !fold.coverOpening || duoFrame.dusk <= 0f || live == null || fold.waitingForPanel) return
        if (still != null && !stillOnExpanded) return
        still = runCatching { live.bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        stillOnExpanded = false
    }

    /**
     * Where the cover's still lies on the inner screen, 1:1 with its hinge edge on the hinge, or null when there is none
     * to show. Only in the natural orientation; rotated, the pictures don't line up.
     */
    private fun stillRect(geometry: FoldGeometry, rotation: Int): RectF? {
        val s = still ?: return null
        if (!fold.expanded || stillOnExpanded || rotation != android.view.Surface.ROTATION_0 || geometry.horizontal) return null
        return RectF(geometry.hingePx, 0f, geometry.hingePx + s.width, s.height.toFloat())
    }

    private fun dropStill() { still = null }

    /** This canvas's pixels per mm of screen (the stage draws at a fraction of the screen's size). */
    private fun pxPerMm(canvasWidth: Int): Float {
        val screen = stage.screenSize().x.takeIf { it > 0 } ?: canvasWidth
        return service.resources.configuration.densityDpi / 25.4f * (canvasWidth.toFloat() / screen)
    }

    // --------------------------------------------------------------------------------------- development triggers

    private var debugReceiver: BroadcastReceiver? = null

    /**
     * Development builds only, and only for a sender holding DUMP (the shell has it; apps do not):
     *
     *     adb shell am broadcast -a com.mccal.folio.dev.STAGE --es run probe      # WP-12's mirror / skip-screenshot probe
     *     adb shell am broadcast -a com.mccal.folio.dev.STAGE --es run sweep      # the effect, in and out, over what is on screen
     *     adb shell am broadcast -a com.mccal.folio.dev.STAGE --es run morph      # the open morph from a made-up icon
     *
     * Nothing here touches the device state; they are pictures only. Results go to `logcat -s FolioStage`.
     */
    private fun registerDebug() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val run = intent.getStringExtra("run")) {
                    "probe" -> stage.probe { Log.i(OverlayStage.TAG, "probe: $it") }
                    "sweep" -> {
                        // --ef hold 0.6 freezes the strength there (for a screenshot); --ez shot true leaves the overlay in
                        // screenshots (so no live frames); --es capture NAME saves what the overlay drew half-way through.
                        stage.debugStayInScreenshots = intent.getBooleanExtra("shot", false)
                        stage.show(SweepDemo(intent.getBooleanExtra("cover", !expandedNow()), intent.getBooleanExtra("live", true),
                            intent.getFloatExtra("hold", -1f), intent.getStringExtra("capture"), intent.getStringExtra("style") == "duo"))
                    }
                    "morph" -> OpenMorph.demo(service, stage, intent.getBooleanExtra("returns", false), intent.getBooleanExtra("shot", false), intent.getStringExtra("capture"))
                    // P-10: can the engine fetch the system's last picture of a background app? --es pkg <package>
                    "snapshot" -> OpenMorph.probeSnapshot(intent.getStringExtra("pkg") ?: "")
                    // WP-53 probe P-22: the shell starts an app with its real window growing out of its icon (or a made-up
                    // square when it has no icon on screen). --es pkg <package>. Home must be in front and unlocked.
                    "launch" -> probeRealOpen(intent.getStringExtra("pkg") ?: "")
                    else -> Log.i(OverlayStage.TAG, "unknown run=$run")
                }
            }
        }
        runCatching {
            service.registerReceiver(receiver, IntentFilter("${service.packageName}.STAGE"), android.Manifest.permission.DUMP, main, Context.RECEIVER_EXPORTED)
            debugReceiver = receiver
        }.onFailure { Log.w(OverlayStage.TAG, "no debug triggers", it) }
    }

    /** Development: asks the shell to open [pkg] with its real window growing out of its icon, and logs what came of it. */
    private fun probeRealOpen(pkg: String) {
        val launch = service.packageManager.getLaunchIntentForPackage(pkg) ?: run { Log.i(OverlayStage.TAG, "real open probe: no launch intent for $pkg"); return }
        val app = FolioSettingsBridge.liveModel?.get()?.state?.value?.apps?.firstOrNull { it.packageName == pkg && it.shortcutId == null }
        val size = stage.screenSize()
        val icon = app?.let { IconBounds.of(it.id) } ?: run {
            val side = (size.x * .14f).toInt()
            android.graphics.Rect((size.x * .62f).toInt(), (size.y * .66f).toInt(), (size.x * .62f).toInt() + side, (size.y * .66f).toInt() + side)
        }
        val radius = runCatching {
            service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
                .getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
        }.getOrNull() ?: 0f
        val user = app?.user?.hashCode() ?: 0
        Log.i(OverlayStage.TAG, "real open probe: $pkg from ${icon.toShortString()} user=$user radius=$radius engine=${FoldShizuku.connected}")
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            val asked = SystemClock.uptimeMillis()
            val problem = FoldShizuku.launch(launch, user, icon, radius)
            Log.i(OverlayStage.TAG, "real open probe: ${if (problem.isEmpty()) "started" else problem} after ${SystemClock.uptimeMillis() - asked} ms; route: ${FoldShizuku.launchRoute()}")
        }
    }

    /** The effect played in and out on a clock, over whatever is on screen. Proves the canvas without a hand on the hinge. */
    private inner class SweepDemo(private val cover: Boolean, private val live: Boolean, private val hold: Float = -1f,
        private var capture: String? = null, private val duo: Boolean = false) : OverlayStage.Scene {
        override val name = "sweep demo"
        override val maxMs = 6_000L
        override val wantsLive get() = live
        private var startedAt = 0L
        private var frames = 0
        private var worstMs = 0L
        private var last = 0L
        private var liveFrames = 0
        private var late = 0
        private var veryLate = 0
        override fun draw(canvas: Canvas, width: Int, height: Int, nowMs: Long, live: LiveFrame?): Boolean {
            if (startedAt == 0L) startedAt = nowMs
            if (last != 0L) { val gap = nowMs - last; worstMs = maxOf(worstMs, gap); if (gap > 12) late++; if (gap > 20) veryLate++ }
            last = nowMs; frames++
            if (live != null) liveFrames++
            val t = (nowMs - startedAt) / DEMO_MS
            if (t >= 1f) return false
            val strength = if (hold >= 0f) hold else if (t < .5f) t * 2f else (1f - t) * 2f
            if (t >= .5f) capture?.let { capture = null; main.post { stage.capture(it) } }
            val rotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0
            val geometry = foldGeometry(rotation, null, width.toFloat(), height.toFloat())
            // --es style duo: the iPhone Duo pane instead, tilting to 90° and back.
            if (duo) duoPainter.draw(canvas, width, height, DuoFrame(strength * 90f, 0f, 0f), cover, geometry, pxPerMm(width), 1f, live?.bitmap, null, null)
            else painter.draw(canvas, width, height, strength, cover, geometry, live?.bitmap)
            return true
        }
        override fun onGone(reason: String) {
            val ms = (last - startedAt).coerceAtLeast(1)
            Log.i(OverlayStage.TAG, "sweep demo: $frames frames in $ms ms (${"%.0f".format(frames * 1000f / ms)} fps), worst gap $worstMs ms, $late gaps over 12 ms of which $veryLate over 20 ms, $liveFrames with a live frame, cover=$cover duo=$duo")
        }
    }

    private companion object {
        /** How long a veil may stand perfectly still before it is taken down (SPEC I-3). */
        const val STILL_MS = 2_500L
        const val DEMO_MS = 3_000f
    }
}

/** What the accessibility service holds: the stage, and the fold effect that plays on it. One call site upstream. */
internal class FoldStageHost(service: AccessibilityService) {
    private val stage = OverlayStage(service)
    private val fold = FoldOverlay(service, stage)
    fun start() { stage.start(); fold.start() }
    fun stop() { fold.stop(); stage.stop() }
}

/**
 * Draws [VeilModel]'s front. With a live frame it is the whole Home effect (frost, dark, glint) over the mirrored
 * pixels, transparent wherever the effect has nothing to say, so the real app shows through sharp and current. Without
 * one it is the dark and the glint alone. The shader is DuoShader's full-width sweep (FoldTransition.kt), reading a
 * bitmap instead of a layer, and writing alpha.
 */
internal class VeilPainter {
    private val gpu = if (Build.VERSION.SDK_INT >= 33) Gpu() else null
    private val plain = Paint()

    fun draw(canvas: Canvas, width: Int, height: Int, m: Float, cover: Boolean, geometry: FoldGeometry, live: Bitmap?) {
        if (m <= 0f || width <= 0 || height <= 0) return
        if (gpu != null && Build.VERSION.SDK_INT >= 33) { gpu.draw(canvas, width, height, m, cover, geometry, live); return }
        // Android 12: no runtime shaders. The same front as a gradient; no glint, no frost.
        val along = if (geometry.horizontal) height.toFloat() else width.toFloat()
        // u = 0 where the dark enters: the free edge on the cover, the moving half's outer edge inside.
        val entersAtEnd = if (cover) !geometry.movingAfterHinge else geometry.movingAfterHinge
        val stops = FloatArray(STOPS) { it / (STOPS - 1f) }
        val colors = IntArray(STOPS) { i ->
            val u = if (entersAtEnd) 1f - stops[i] else stops[i]
            Color.argb((VeilModel.veilAlpha(m, u) * 255).toInt().coerceIn(0, 255), 0, 0, 0)
        }
        plain.shader = if (geometry.horizontal) LinearGradient(0f, 0f, 0f, along, colors, stops, Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, along, 0f, colors, stops, Shader.TileMode.CLAMP)
        canvas.drawPaint(plain)
    }

    @RequiresApi(33)
    private class Gpu {
        private val shader = RuntimeShader(SOURCE)
        private val paint = Paint().apply { this.shader = this@Gpu.shader }
        private val blank = BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val fit = Matrix()

        fun draw(canvas: Canvas, width: Int, height: Int, m: Float, cover: Boolean, geometry: FoldGeometry, live: Bitmap?) {
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("axis", if (geometry.horizontal) 1f else 0f)
            shader.setFloatUniform("side", if (geometry.movingAfterHinge) 1f else -1f)
            shader.setFloatUniform("m", m)
            shader.setFloatUniform("maxRadius", width * .045f)
            shader.setFloatUniform("cover", if (cover) 1f else 0f)
            if (live != null && live.width > 0 && live.height > 0) {
                val frame = BitmapShader(live, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                frame.filterMode = BitmapShader.FILTER_MODE_LINEAR
                fit.setScale(width.toFloat() / live.width, height.toFloat() / live.height)
                frame.setLocalMatrix(fit)
                shader.setInputShader("frame", frame)
                shader.setFloatUniform("live", 1f)
            } else {
                shader.setInputShader("frame", blank)
                shader.setFloatUniform("live", 0f)
            }
            canvas.drawPaint(paint)
        }

        companion object {
            // blur() is upstream Folio's 24-tap disk (McCal-Codes/folio, MIT); blurLite and the front are Fold8Duo's,
            // as in DuoShader. Output is premultiplied.
            private const val SOURCE = """
                uniform shader frame;
                uniform float2 size;
                uniform float m;
                uniform float maxRadius;
                uniform float cover;
                uniform float axis;
                uniform float side;
                uniform float live;

                half4 blur(float2 p, float r) {
                    if (r < 0.75) return frame.eval(p);
                    float a = r * 0.33; float b = r * 0.66; float c = r;
                    float a7 = a * 0.7071; float b7 = b * 0.7071; float c7 = c * 0.7071;
                    half4 sum = frame.eval(p) * 0.08;
                    sum += (frame.eval(p + float2(a, 0.0)) + frame.eval(p + float2(-a, 0.0)) + frame.eval(p + float2(0.0, a)) + frame.eval(p + float2(0.0, -a))
                          + frame.eval(p + float2(a7, a7)) + frame.eval(p + float2(-a7, a7)) + frame.eval(p + float2(a7, -a7)) + frame.eval(p + float2(-a7, -a7))) * 0.05;
                    sum += (frame.eval(p + float2(b, 0.0)) + frame.eval(p + float2(-b, 0.0)) + frame.eval(p + float2(0.0, b)) + frame.eval(p + float2(0.0, -b))
                          + frame.eval(p + float2(b7, b7)) + frame.eval(p + float2(-b7, b7)) + frame.eval(p + float2(b7, -b7)) + frame.eval(p + float2(-b7, -b7))) * 0.04;
                    sum += (frame.eval(p + float2(c, 0.0)) + frame.eval(p + float2(-c, 0.0)) + frame.eval(p + float2(0.0, c)) + frame.eval(p + float2(0.0, -c))
                          + frame.eval(p + float2(c7, c7)) + frame.eval(p + float2(-c7, c7)) + frame.eval(p + float2(c7, -c7)) + frame.eval(p + float2(-c7, -c7))) * 0.025;
                    return sum;
                }

                half4 blurLite(float2 p, float r) {
                    if (r < 0.75) return frame.eval(p);
                    float d = r * 0.6; float d7 = d * 0.7071;
                    half4 sum = frame.eval(p) * 0.2;
                    sum += (frame.eval(p + float2(d, 0.0)) + frame.eval(p + float2(-d, 0.0)) + frame.eval(p + float2(0.0, d)) + frame.eval(p + float2(0.0, -d))
                          + frame.eval(p + float2(d7, d7)) + frame.eval(p + float2(-d7, d7)) + frame.eval(p + float2(d7, -d7)) + frame.eval(p + float2(-d7, -d7))) * 0.1;
                    return sum;
                }

                half4 main(float2 p) {
                    float mc = clamp(m, 0.0, 1.0);
                    float coord = axis < 0.5 ? p.x : p.y;
                    float extent = axis < 0.5 ? size.x : size.y;
                    // u runs along the front's path, 0 where the dark enters. On the cover that is the free edge and it
                    // runs to the hinge; inside it is the moving half's outer edge and it runs to the still half's far edge.
                    float u; float glintGain;
                    if (cover > 0.5) { u = clamp(side < 0.0 ? (extent - coord) / extent : coord / extent, 0.0, 1.0); glintGain = 0.0; }
                    else { u = clamp(side < 0.0 ? coord / extent : (extent - coord) / extent, 0.0, 1.0); glintGain = 0.3; }
                    float w = 0.35;
                    float f = pow(mc, 1.4) * (1.0 + w);
                    float dark = 1.0 - smoothstep(f - w, f, u);
                    float frost = 1.0 - smoothstep(f - w, f + 0.5 * w, u);
                    float bandF = (u - f) / 0.07;
                    float glint = exp(-bandF * bandF) * glintGain * mc * (1.0 - mc);
                    if (live < 0.5) {
                        float a = max(0.9 * dark, glint);
                        return half4(half3(glint), a);
                    }
                    float rf = maxRadius * frost * (0.35 + 0.65 * mc) * max(1.0, m);
                    half4 cf;
                    if (dark > 0.8) { cf = blurLite(p, rf); } else { cf = blur(p, rf); }
                    half3 rgb = cf.rgb * (1.0 - 0.9 * dark) + half3(glint);
                    // Where there is no frost at all the real app shows through: sharp, and not a frame late.
                    float a = smoothstep(0.0, 0.06, frost);
                    return half4(rgb * a, a);
                }
            """
        }
    }

    private companion object { const val STOPS = 12 }
}
