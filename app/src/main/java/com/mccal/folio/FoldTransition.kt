package com.mccal.folio

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.exp
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import com.mccal.folio.duo.ClosingTail
import com.mccal.folio.duo.DuoFrame
import com.mccal.folio.duo.DuoPaneShader
import com.mccal.folio.duo.DuoTiming
import com.mccal.folio.duo.FoldStyle
import com.mccal.folio.duo.FoldStyles
import com.mccal.folio.duo.PaneSpring

/**
 * iPhone Duo–style fold effect, as dynamic as a Galaxy Z Fold allows.
 *
 * Android doesn't require the public hinge sensor to be continuous, and on a Galaxy Z Fold8 it reports only 0°, 90°
 * and 180° to apps (Samsung's finer readings stay with its own components); One UI decides when displays switch.
 * So on stepped sensors the effect is *step-anchored and speed-adaptive*: each real step is a checkpoint, the motion
 * between checkpoints is predicted from how fast this person folds (learned over time), and a late or early step
 * bends the animation instead of snapping it. Phones whose sensor proves continuous follow the angle itself
 * (see [HingeTracker]).
 *
 * Visual model (from the MIT Three.js recreation): the half left of the hinge is blurred with
 * radius ∝ m·e^1.35 and darkened toward its outer edge; m is 1 half-folded and 0 flat.
 */
@Composable
fun FoldTransitionHost(enabled: Boolean = true, intensity: Float = 1f, stayAwake: Boolean = true,
    snapshotMorph: Boolean = false, haptics: Boolean = true, content: @Composable () -> Unit) {
    // Which screen we're on, by size in both dimensions, so rotating the cover to landscape never looks like an unfold.
    val expanded = LocalConfiguration.current.fitsRegularHomeLayout()
    val view = LocalView.current
    val context = LocalContext.current
    // Where the hinge is and which half moves, from the real fold and the display's rotation, so the effect is
    // right in portrait, upside down and on a rotated cover, not just in the unfolded landscape it was tuned in.
    val hinge = LocalHinge.current
    val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) DuoShader() else null }
    val fold = remember { FoldTimeline(context) }
    fold.stayAwake = stayAwake
    FoldSmoothness.load(context)
    // Fold8Duo: which animation plays — the accepted sweep, or the iPhone Duo replica (duo/DuoFold.kt).
    FoldStyles.load(context)
    val duo = FoldStyles.current.value == FoldStyle.DUO
    val duoShader = remember { if (Build.VERSION.SDK_INT >= 33) DuoPaneShader() else null }
    val spring = remember { PaneSpring() }
    // The inner pane's tilt comes from the real angle; this spring gives it its inertia and the settle at flat.
    val tiltSpring = remember { PaneSpring() }
    var duoFrame by remember { mutableStateOf(DuoFrame.NONE) }
    // One light tick as the hinge passes halfway, opening or closing (idea from FoldFX).
    val tick by androidx.compose.runtime.rememberUpdatedState(enabled && haptics)
    fold.onHalfway = { if (tick) MotionTokens.Haptics.halfway(view) }
    // WP-56 (SPEC §3.3): a tick on reaching flat, a thud on fully closed, all through one limiter.
    fold.onReachedFlat = { if (tick) MotionTokens.Haptics.flat(view) }
    fold.onReachedClosed = { if (tick) MotionTokens.Haptics.closed(view) }
    // m: 0 = clean, 1 = fully half-folded look. cover = whole-screen mode on the cover display.
    var m by remember { mutableFloatStateOf(0f) }

    // Screenshot morph (fallback style): snapshots of Folio's own screen taken the moment the hinge
    // starts moving, drawn over the new display and melted into the live UI. Memory only, never saved.
    val contentLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var coverShot by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var innerShot by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var morph by remember { mutableFloatStateOf(1f) } // 0 = snapshot fully shown, 1 = done
    // Fold8Duo: the iPhone Duo style always carries a still across the swap (its position lock); the sweep only with
    // upstream's Screenshot morph on. The stills are taken either way; the clock morph below is upstream's, sweep only.
    val stills by androidx.compose.runtime.rememberUpdatedState(enabled && (snapshotMorph || duo))
    val morphEnabled by androidx.compose.runtime.rememberUpdatedState(enabled && snapshotMorph && !duo)
    fold.onOpeningStarted = { if (stills && !fold.expanded) scope.launch {
        // Fold8Duo: a GPU readback of the whole cover layer, right at the start of an opening — timed, because the peer's
        // per-fold frame counts put one or two long frames in every fast fold and this is a suspect (WP-51 r2 notes).
        val startedAt = SystemClock.uptimeMillis()
        FoldTrace.event("cover still: start=$startedAt")
        coverShot = runCatching { contentLayer.toImageBitmap() }.getOrNull()
        FoldTrace.event("cover still: landed ms=${SystemClock.uptimeMillis() - startedAt} size=${coverShot?.width}x${coverShot?.height}")
    } }
    // Folding, the iPhone Duo style shows the cover's own picture coming into focus (as the film does): no still that way.
    fold.onClosingStarted = { if (morphEnabled && fold.expanded) scope.launch { innerShot = runCatching { contentLayer.toImageBitmap() }.getOrNull() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // WP-57 (dev builds): log which half the owner moves, from the gyro against the real hinge, during his own folds.
    val motionLog = remember { if (context.packageName.endsWith(".dev")) FoldMotionLog(context) else null }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> { fold.start(); motionLog?.start() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { fold.stop(); motionLog?.stop() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); fold.stop(); motionLog?.stop() }
    }

    // Decide during composition so the very first frame on the new display is already covered.
    if (expanded != fold.expanded) {
        fold.expanded = expanded
        fold.onDisplaySwitched(SystemClock.uptimeMillis())
        m = if (expanded) START_M_ON_UNFOLD else COVER_START_M
        spring.snap(m)
        // Cover the very first frame on the new display with the snapshot (held until the panel is lit).
        if (morphEnabled && (if (expanded) coverShot != null else innerShot != null)) morph = 0f
    }

    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
        var litFrames = 0
        var lastFrame = 0L
        while (true) {
            if (!fold.busy && m == 0f) { lastFrame = 0L; kotlinx.coroutines.delay(IDLE_POLL_MS); continue }
            withFrameNanos { frame ->
                val now = SystemClock.uptimeMillis()
                val dt = if (lastFrame == 0L) 16f else ((frame - lastFrame) / 1_000_000f).coerceIn(1f, 64f)
                lastFrame = frame
                if (fold.waitingForPanel) {
                    val lit = view.display?.state == Display.STATE_ON
                    litFrames = if (lit) litFrames + 1 else 0
                    if (litFrames >= 2 || now - fold.switchedAt > LIT_TIMEOUT_MS) {
                        fold.onPanelLit(now); litFrames = 0
                        if (morphEnabled && (if (fold.expanded) coverShot != null else innerShot != null)) { fold.morphFrom = now; morph = 0f }
                    }
                }
                val target = fold.targetM(now)
                val duoNow = FoldStyles.current.value == FoldStyle.DUO
                // Follow the target closely but never jump: small time constant, frame-rate independent.
                // Fold8Duo: the iPhone Duo style follows through a spring instead — a little inertia, and the settle at flat.
                val next = if (duoNow) spring.step(target, dt) else m + (target - m) * (1f - exp(-dt / FOLLOW_MS))
                m = if (target == 0f && next < .003f && (!duoNow || spring.settled(0f))) 0f else next
                if (m == 0f) spring.snap(0f)
                if (duoNow) {
                    val raw = fold.duoFrame(now, m, target)
                    duoFrame = if (!fold.expanded || fold.waitingForPanel || raw.idle) { tiltSpring.snap(raw.tiltDeg); raw }
                        else raw.copy(tiltDeg = tiltSpring.step(raw.tiltDeg, dt))
                    // The cover's still has melted into the live UI (or was never needed): let it go.
                    if (duoFrame.still <= 0f && fold.expanded && !fold.waitingForPanel && coverShot != null) coverShot = null
                } else if (duoFrame != DuoFrame.NONE) duoFrame = DuoFrame.NONE
                FoldTrace.sample(now) { "m=${"%.2f".format(m)} target=${"%.2f".format(target)} angle=${"%.0f".format(fold.traceAngle())} expanded=${fold.expanded} closing=${fold.closing} duo=$duoFrame" }
                if (fold.morphFrom >= 0) {
                    val t = ((now - fold.morphFrom) / (if (fold.expanded) MORPH_UNFOLD_MS else MORPH_FOLD_MS)).coerceIn(0f, 1f)
                    morph = easeInOutSine(t)
                    if (t >= 1f) { fold.morphFrom = -1L; morph = 1f; if (fold.expanded) coverShot = null }
                }
            }
        }
    } }

    // The Duo shader always drives the rotating half; the iPhone Duo style adds the still right half on top.
    val useBlurEffect = enabled
    // Fold8Duo: in half-resolution mode (FoldSmoothness) the screen is drawn sharp as it is and the effect's frosted and
    // dark share is rendered at half size on top: a quarter of the shader's work, the same look where it's clear.
    // The iPhone Duo style always takes that route: its blur is a tenth of a half wide, and what it blurs needs no more
    // resolution than that, while the sharp half stays full-resolution underneath.
    val halfRes = shader != null && Build.VERSION.SDK_INT >= 33 && (FoldSmoothness.halfRes.value || duo)
    val effectLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    // Fold8Duo: the iPhone Duo style draws whenever its frame has anything to say; the sweep while its strength is above zero.
    val drawing = if (duo) !duoFrame.idle else m > 0f
    // The Duo effect wraps both the live screen and the still picture (so the cover's blur applies to both),
    // while the recording below it captures the clean screen (a snapshot must never have blur baked in).
    Box(Modifier.fillMaxSize().then(
        if (shader != null) Modifier.graphicsLayer {
            val geometry = foldGeometry(rotation, hinge, size.width, size.height)
            renderEffect = if (halfRes || !useBlurEffect || !drawing || Build.VERSION.SDK_INT < 33) null
                else if (duo && duoShader != null) duoShader.effect(size.width, size.height, duoFrame, cover = !fold.expanded, geometry = geometry,
                    pxPerMm = density * PX_PER_MM_AT_DENSITY_1, intensity = intensity)
                else shader.effect(size.width, size.height, (m * intensity).coerceIn(0f, 1.5f),
                    cover = !fold.expanded, geometry = geometry, fullWidth = FOLD_FULL_WIDTH_SWEEP)
            // The open screen settles up to full size as it clears, and eases back down as it folds. (Not in the iPhone
            // Duo style: its whole point is that the picture stays put.)
            val settle = if (useBlurEffect && fold.expanded && !duo) 1f - FOLD_SCALE * m.coerceIn(0f, 1f) else 1f
            scaleX = settle; scaleY = settle
        }.then(if (halfRes) Modifier.drawWithContent {
            drawContent()
            if (useBlurEffect && drawing && Build.VERSION.SDK_INT >= 33) {
                val w = (size.width / 2f).toInt().coerceAtLeast(1)
                val h = (size.height / 2f).toInt().coerceAtLeast(1)
                effectLayer.record(androidx.compose.ui.unit.IntSize(w, h)) {
                    scale(.5f, .5f, pivot = androidx.compose.ui.geometry.Offset.Zero) { this@drawWithContent.drawContent() }
                }
                val full = foldGeometry(rotation, hinge, size.width, size.height)
                val half = FoldGeometry(full.horizontal, full.hingePx / 2f, full.movingAfterHinge)
                effectLayer.renderEffect = if (duo && duoShader != null) duoShader.effect(w.toFloat(), h.toFloat(), duoFrame, cover = !fold.expanded,
                        geometry = half, pxPerMm = density * PX_PER_MM_AT_DENSITY_1 / 2f, intensity = intensity, overlay = true)
                    else shader.effect(w.toFloat(), h.toFloat(), (m * intensity).coerceIn(0f, 1.5f),
                        cover = !fold.expanded, geometry = half, fullWidth = FOLD_FULL_WIDTH_SWEEP, overlay = true)
                scale(2f, 2f, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawLayer(effectLayer) }
            }
        } else Modifier)
        else Modifier.drawWithContent {
            drawContent()
            if (useBlurEffect && m > 0f) {
                if (fold.expanded) drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = m),
                    .5f to Color.Transparent, startX = 0f, endX = size.width))
                else drawRect(Color.Black.copy(alpha = .5f * m))
            }
            if (duo && duoFrame.dusk > 0f) drawRect(Color.Black.copy(alpha = duoFrame.dusk.coerceIn(0f, 1f)))
        })) {
        Box(Modifier.fillMaxSize()
            // Keep a live recording of Folio's screen so a snapshot can be taken instantly when folding starts.
            .then(if (stills) Modifier.drawWithContent {
                contentLayer.record { this@drawWithContent.drawContent() }
                drawLayer(contentLayer)
            } else Modifier)) { content() }
        // The still picture maps the cover 1:1 onto the inner half only in the natural orientation; rotated, the
        // pictures don't line up, so the blur carries the transition on its own.
        if (morphEnabled && morph < 1f && rotation == android.view.Surface.ROTATION_0) SnapshotMorph(fold.expanded, coverShot, innerShot) { stillAlpha(morph) }
        // Fold8Duo, iPhone Duo style: the same still, held and melted by the hinge angle (DuoTiming) rather than a clock.
        if (duo && duoFrame.still > 0f && rotation == android.view.Surface.ROTATION_0) SnapshotMorph(fold.expanded, coverShot, innerShot) { duoFrame.still }
        // Whole screen dims as it folds, like the display powering down with the hinge.
        if (enabled && !duo && fold.closing && !fold.followsHinge) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = (m * FOLD_DIM).coerceIn(0f, FOLD_DIM)))
        }
    }
}

/** Upstream's clock morph: hold the still picture while the rotating half clears, then hand over to the live UI. */
private fun stillAlpha(p: Float) = 1f - ((p - STILL_HOLD) / (1f - STILL_HOLD)).coerceIn(0f, 1f)

/**
 * iPhone Duo's trick (per hands-on reviews and chuspeeism/iphone-duo): content doesn't move. The panel with the
 * rear cameras stays put, so the cover screen's picture is shown on the inner screen's right half at the same
 * physical size and position (hinge-side edge on the hinge, top-aligned) while the left half comes into focus
 * (the Duo shader on the live screen). Folding does the reverse. The still picture then fades into the live UI.
 * Both Fold screens share a density, so 1:1 pixels means the same physical size.
 */
@Composable
private fun SnapshotMorph(expanded: Boolean, coverShot: androidx.compose.ui.graphics.ImageBitmap?,
    innerShot: androidx.compose.ui.graphics.ImageBitmap?, opacity: () -> Float) {
    if (expanded) coverShot?.let { shot ->
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = opacity() }) {
            val hinge = size.width / 2
            clipRect(left = hinge) {
                drawImage(shot, dstOffset = androidx.compose.ui.unit.IntOffset(hinge.toInt(), 0),
                    dstSize = androidx.compose.ui.unit.IntSize(shot.width, shot.height))
            }
        }
    } else innerShot?.let { shot ->
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = opacity() }) {
            // The inner right half, 1:1, with the hinge on the cover's left edge.
            val hinge = shot.width / 2
            drawImage(shot, srcOffset = androidx.compose.ui.unit.IntOffset(hinge, 0),
                srcSize = androidx.compose.ui.unit.IntSize(shot.width - hinge, shot.height),
                dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                dstSize = androidx.compose.ui.unit.IntSize(shot.width - hinge, shot.height))
        }
    }
}

/** Hinge steps + learned timing → target effect strength over time. */
internal class FoldTimeline(context: Context, private val devSocket: Boolean = true) : SensorEventListener, HingeFeed.Listener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val hinge: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private val prefs = context.getSharedPreferences("folio", 0)

    var expanded = false
    var stayAwake = true
    var switchedAt = -1L; private set
    var waitingForPanel = false; private set

    // What this phone's hinge sensor reports, learned from its readings and remembered. The Fold8's public sensor is
    // stepped (0/90/180), so the motion between steps is predicted from learned timing; a continuous sensor is
    // followed directly.
    private val tracker = HingeTracker(if (hinge == null) HingeCapability.POSTURE_ONLY
        else if (prefs.getString(CAPABILITY_KEY, null) == HingeCapability.CONTINUOUS.name) HingeCapability.CONTINUOUS else HingeCapability.STEPPED)
    private val continuous get() = tracker.capability == HingeCapability.CONTINUOUS
    // Fold8Duo: what the public sensor itself can do, restored whenever a real-angle feed goes away.
    private var sensorCapability = tracker.capability
    // Fold8Duo: with a real angle the effect follows the hinge in BOTH directions (see [followHinge]). engaged = it is
    // following right now; engage = the same, eased, so picking the hinge up or letting it go is never a jump.
    private var engaged = false
    private var engage = 0f
    private var engageAt = 0L
    private var lastDirection = 0
    // Fold8Duo: how long the front screen's hand-over runs, from the feeder's opening signal; 0 = ride the angle.
    private var coverSweepMs = 0f
    // Fold8Duo: the real hinge angle when the front screen lit after a fold. Well above zero means early cover lit
    // it with travel still to go, and the come-back rides that travel.
    private var coverLitAngle = 0f
    /** True while the real angle, not an episode timer, decides the picture on the open screen. */
    val followsHinge get() = expanded && HingeFeed.connected && HingeFeed.predicted() != null
    private var angleAt = 0L
    // A real movement, not sensor jitter: what the stall checks measure from on continuous sensors.
    private var movedAt = 0L
    private var movedFrom = 0f
    // Highest angle while not folding, and lowest while folding: a fold or a reopen is a real change from these.
    private var peak = 0f
    private var trough = 0f

    // Unfold (inner display): lit → flat.
    private var litAt = -1L
    private var flatAt = -1L
    private var litAngle = HingeTracker.FLAT_ENTER_DEG
    private var predictedOpenMs = prefs.getFloat("fold_open_ms", 520f)

    // Fold (inner display): 180→90 step → 0 step.
    private var closeStartAt = -1L
    private var closedAt = -1L
    private var reopenedAt = -1L
    private var predictedCloseMs = prefs.getFloat("fold_close_ms", 650f)

    // Cover display after folding, and while starting to open from the cover.
    private var coverLitAt = -1L
    private var coverOpeningAt = -1L
    // Fold8Duo: when the current panel lit (either panel), for the iPhone Duo style's dawn and stills; -1 before it has.
    private var duoLitAt = -1L
    private val appContext = context.applicationContext

    var onOpeningStarted: (() -> Unit)? = null
    var onClosingStarted: (() -> Unit)? = null
    var onHalfway: (() -> Unit)? = null
    /** WP-56: the hinge arrived at flat, or at closed (the public sensor's own 180 and 0). */
    var onReachedFlat: (() -> Unit)? = null
    var onReachedClosed: (() -> Unit)? = null
    /** Uptime when the screenshot morph started on the new display, or -1. */
    var morphFrom = -1L
    /** Folding from the open screen right now. */
    val closing get() = closeStartAt >= 0 && expanded
    /** Fold8Duo: opening from the front screen right now, before the panels swap. */
    val coverOpening get() = coverOpeningAt >= 0 && !expanded
    val busy get() = morphFrom >= 0 || waitingForPanel || litAt >= 0 || closeStartAt >= 0 || reopenedAt >= 0 || coverLitAt >= 0 || coverOpeningAt >= 0 ||
        engaged || engage > 0f

    fun start() { hinge?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }; HingeFeed.attach(appContext, this, devSocket) }
    fun stop() { sensors?.unregisterListener(this); HingeFeed.detach(this) }

    fun onDisplaySwitched(now: Long) {
        switchedAt = now; waitingForPanel = true
        engaged = false; engage = 0f; lastDirection = 0   // following belongs to the open screen; never carry it to the cover
        if (expanded) coverSweepMs = 0f
        FoldTrace.event("display switched expanded=$expanded feed=${HingeFeed.connected} angle=${traceAngle()}")
        litAt = -1L; flatAt = -1L; closeStartAt = -1L; closedAt = -1L; reopenedAt = -1L; coverLitAt = -1L; coverOpeningAt = -1L
        duoLitAt = -1L
    }

    fun onPanelLit(now: Long) {
        waitingForPanel = false
        duoLitAt = now
        if (expanded) { litAt = now; litAngle = tracker.visual ?: HingeTracker.FLAT_ENTER_DEG; if (tracker.flat) flatAt = now } else coverLitAt = now
        if (expanded) { engaged = true; engage = 1f; engageAt = now; movedAt = now; lastDirection = 0 }
        else {
            coverLitAngle = if (tracker.closed) 0f else HingeFeed.predicted() ?: 0f
            FoldTrace.event("front screen lit at ${"%.0f".format(coverLitAngle)}° — ${if (coverLitAngle > COVER_REVEAL_DONE_DEG + 6f) "come-back rides the hinge" else "come-back is timed"}")
        }
        FoldTrace.event("panel lit expanded=$expanded after=${now - switchedAt}ms litAngle=$litAngle feed=${HingeFeed.connected} hingeBound=${expanded && HingeFeed.connected}")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        // Fold8Duo: with a real angle connected, the public sensor's 0 and 180 stay authoritative (the HAL's stream
        // stops a few degrees short of both ends), but its late "90" would drag the picture backwards: drop it.
        if (HingeFeed.connected && value > ENDS_TOLERANCE_DEG && value < 180f - ENDS_TOLERANCE_DEG) return
        onAngle(value, event.timestamp, fromSensor = true)
    }

    // Fold8Duo: the system's own "the phone is opening" moment. It has no value to misjudge (unlike the HAL's first
    // angle word) and the panel swap follows it on a fixed schedule, so the front screen's sweep gets one steady
    // window — finishing just before the cover goes off — however fast or slow the hand is.
    override fun onOpeningSignal(swapInMs: Int) {
        if (expanded || coverOpeningAt >= 0) return
        coverOpeningAt = SystemClock.uptimeMillis()
        coverSweepMs = if (swapInMs > 0) (swapInMs - COVER_SWEEP_LEAD_MS).toFloat().coerceIn(COVER_SWEEP_MIN_MS, COVER_SWEEP_MAX_MS) else 0f
        onOpeningStarted?.invoke()
        FoldTrace.event("cover opening (system signal) swapIn=${swapInMs}ms sweep=${coverSweepMs.toInt()}ms")
    }
    override fun onClosedSignal() {
        if (!expanded && coverOpeningAt >= 0) { coverOpeningAt = -1L; FoldTrace.event("cover opening abandoned: closed again") }
    }

    // Fold8Duo: the real angle drives the same logic the sensor does.
    override fun onHingeAngle(degrees: Float) = onAngle(degrees, SystemClock.elapsedRealtimeNanos(), fromSensor = false)
    override fun onFeedChanged(connected: Boolean) {
        tracker.assume(if (connected) HingeCapability.CONTINUOUS else sensorCapability)
        FoldTrace.event("hinge feed ${if (connected) "CONNECTED — following the real angle" else "gone — back to $sensorCapability timing"}")
    }

    private fun onAngle(value: Float, timestampNanos: Long, fromSensor: Boolean) {
        val now = SystemClock.uptimeMillis()
        val previous = tracker.raw
        val wasFlat = tracker.flat
        val wasClosed = tracker.closed
        // Fold8Duo: a close's late last word, landing after the sensor's 0, is dropped whole (duo/ClosingTail.kt).
        if (ClosingTail.dropOnCover(!fromSensor && HingeFeed.closingTail, expanded, wasClosed)) {
            FoldTrace.event("late closing word ignored: angle=$value, the engine's words were still falling"); return
        }
        // Only the sensor's own readings may teach (and save) what the sensor can do.
        if (tracker.feed(value, timestampNanos) && fromSensor) {
            sensorCapability = tracker.capability
            prefs.edit().putString(CAPABILITY_KEY, tracker.capability.name).apply()
        }
        angleAt = now
        if (previous == null) { peak = value; movedFrom = value; movedAt = now; return }
        if (previous == value) return
        // A phone held still for a while starts a fresh reference, so an old maximum can't turn a small move into a fold.
        if (now - movedAt > STALL_MS && closeStartAt < 0) peak = previous
        if (kotlin.math.abs(value - movedFrom) >= MOVE_DEG) { movedFrom = value; movedAt = now }
        if (HingeTracker.crossedHalfway(previous, value)) onHalfway?.invoke()
        if (tracker.flat && !wasFlat) onReachedFlat?.invoke()
        if (tracker.closed && !wasClosed) onReachedClosed?.invoke()
        if (expanded && !fromSensor) {
            val direction = if (kotlin.math.abs(value - previous) < DIRECTION_MIN_DEG) lastDirection else if (value > previous) 1 else -1
            if (engaged && lastDirection != 0 && direction != lastDirection)
                FoldTrace.event("direction change at $value°: now ${if (direction > 0) "opening" else "folding"} — still following")
            lastDirection = direction
            // Resting part-open released the picture (flex mode must stay usable). Folding further picks it up again
            // at once — the HAL's first word after a rest already means ~10° of travel. Opening further does not:
            // nobody wants a screen they were reading to go dark because they opened it the rest of the way.
            if (!engaged && value < previous - REENGAGE_DROP_DEG) {
                engaged = true; engageAt = now
                FoldTrace.event("re-engaged: folding from $previous° (now $value°)")
            }
        }
        if (expanded) {
            when {
                // Reached flat while revealing: learn how long lit → flat takes for this person.
                tracker.flat && !wasFlat && litAt >= 0 && flatAt < 0 -> {
                    flatAt = now
                    learnOpen((now - litAt).toFloat())
                }
                // Started folding from flat: keep One UI from sleeping, and start the fold-away.
                wasFlat && !tracker.flat -> startClosing(now, value)
                // Nearly closed: learn how long the fold takes, and make sure the bridge is up.
                tracker.closed && closeStartAt >= 0 && closedAt < 0 -> {
                    closedAt = now
                    learnClose((now - closeStartAt).toFloat())
                    if (stayAwake) FoldBridgeActivity.start(appContext)
                }
                // Folding that didn't start from flat (e.g. from half-open): only a real drop counts,
                // so sensor jitter or adjusting a propped phone never starts the bridge.
                !tracker.flat && closeStartAt < 0 && peak - value >= MIN_FOLD_DROP_DEG -> {
                    startClosing(now, value)
                    if (tracker.closed) closedAt = now
                }
                // Opened back up before closing.
                closeStartAt >= 0 && value - trough >= REOPEN_DEG -> {
                    closeStartAt = -1L; closedAt = -1L; reopenedAt = now; peak = value
                    FoldBridgeActivity.cancel()
                }
            }
            if (closeStartAt >= 0) trough = minOf(trough, value) else peak = maxOf(peak, value)
        } else {
            when {
                // Starting to open on the cover: blur the whole cover screen.
                wasClosed && !tracker.closed -> if (coverOpeningAt < 0) { coverOpeningAt = now; onOpeningStarted?.invoke(); FoldTrace.event("cover opening (left closed band) angle=$value") }
                // Fold8Duo: the HAL only speaks once the lid has left its magnet, so a rising first word IS the opening.
                // Waiting for the closed band's 12° exit would be too late: early light has swapped panels by then.
                !fromSensor && coverOpeningAt < 0 && value > previous && value >= FEED_OPENING_DEG -> {
                    coverOpeningAt = now; onOpeningStarted?.invoke(); FoldTrace.event("cover opening (first HAL word) angle=$value")
                }
                // Fold8Duo: the hinge turned back before the panels swapped — a peek, or an opening the engine let go of.
                // The front screen is staying: give it back now. (Measured 2026-09-22: without this it stayed dark until
                // the stall timer, 2 s.)
                !fromSensor && coverOpeningAt >= 0 && value < previous - DIRECTION_MIN_DEG -> {
                    coverOpeningAt = -1L; FoldTrace.event("cover opening abandoned: hinge turned back at $value°")
                }
                tracker.closed && (fromSensor || value <= previous) -> coverOpeningAt = -1L
            }
        }
    }

    /** Fold8Duo: the angle the effect is drawing from, for [FoldTrace]. */
    fun traceAngle(): Float = HingeFeed.predicted() ?: tracker.visual ?: Float.NaN

    /**
     * Fold8Duo, iPhone Duo style: what its renderers draw this frame, from the same episode state as [targetM] — the
     * moving pane's tilt, the whole-screen dusk around the swap, and the position-lock still's alpha. [m] is the strength
     * the host is showing (spring-followed: it sets the tilt); [target] is this frame's [targetM] (it sets the cover's
     * dusk, which must not lag the swap). The reasoning and the numbers are in duo/DuoPaneModel.kt ([DuoTiming]).
     */
    fun duoFrame(now: Long, m: Float, target: Float): DuoFrame {
        if (!busy && m <= 0f) return DuoFrame.NONE
        val mc = m.coerceIn(0f, 1f)
        val real = HingeFeed.predicted()
        val sinceLit = if (duoLitAt < 0) Float.MAX_VALUE else (now - duoLitAt).toFloat()
        if (expanded) {
            val angle = real ?: DuoTiming.angleFromStrength(mc)
            val dawn = DuoTiming.dawn(sinceLit)
            val dusk = when {
                waitingForPanel || closedAt >= 0 -> 1f
                closing -> maxOf(DuoTiming.closingDusk(angle, DuoTiming.closeDuskEndDeg(earlyCoverActive(), FoldShizuku.earlyCoverDeg(appContext).toFloat())), dawn)
                else -> dawn
            }
            val still = if (waitingForPanel) 1f else if (closing) 0f else DuoTiming.stillWhileOpening(angle, sinceLit)
            // The film's fold effect starts the instant the hinge leaves flat: with the real angle the tilt is read from
            // it directly (the sweep's flat band would hold it at zero for the first ~15° of a fold), eased in and out by
            // the same rest-release the sweep uses ([engage]); without the feed the timed strength stands in.
            val hingeTilt = if (real != null && (engaged || !tracker.flat)) DuoTiming.innerTiltFromAngle(real) * easeInOutSine(engage)
                else DuoTiming.innerTilt(mc)
            // A fast open outruns the panel: after it lights, never less than the arrival floor (DuoTiming.ARRIVAL_*).
            val tilt = if (real != null && !closing) maxOf(hingeTilt, DuoTiming.arrivalTilt(sinceLit)) else hingeTilt
            return DuoFrame(tilt, dusk, still)
        }
        // The front screen: its few lit degrees carry the Duo's whole cover look, so the hand-over strength (which rides
        // the hinge, or the clock to the swap) stands for the glass's tilt — see DuoTiming.COVER_TILT_SPAN_DEG.
        val coverTilt = mc * DuoTiming.COVER_TILT_SPAN_DEG
        return when {
            waitingForPanel -> DuoFrame(coverTilt, 1f, 0f)
            // Opening: the glass tilts away and the picture stretches from the hinge as the front screen hands over.
            coverOpeningAt >= 0 -> DuoFrame(coverTilt, DuoTiming.coverDusk(target), 0f)
            // Back: the front screen lights while the phone is still shutting (early cover) or once it is shut, and its
            // come-back settles the glass flat over the front screen's own picture — as the film's cover does, heavily
            // frosted and dark at its free edge when it appears, clearing as it flattens. No still this way.
            coverLitAt >= 0 -> DuoFrame(coverTilt, DuoTiming.dawn(sinceLit), 0f)
            else -> DuoFrame.NONE
        }
    }

    /** Fold8Duo: the engine lights the front screen early while folding (FoldShizuku's "Light the screens early"). */
    private fun earlyCoverActive() = HingeFeed.connected && FoldShizuku.enabled(appContext) && FoldShizuku.earlyLight(appContext)

    private fun startClosing(now: Long, value: Float) {
        closeStartAt = now; closedAt = -1L; reopenedAt = -1L; trough = value
        FoldTrace.event("closing started angle=$value feed=${HingeFeed.connected} continuous=$continuous")
        onClosingStarted?.invoke()
        if (stayAwake) FoldBridgeActivity.start(appContext)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Fold8Duo: with a real angle, the open screen's picture is a function of the hinge and nothing else — one curve,
     * clear at flat and fully dark by [HINGE_BOUND_DARK_DEG] — so opening, folding, stopping half-way and changing
     * your mind all just follow the hand. (Upstream runs opening and folding as separate timed episodes, because
     * stepped sensors leave it no choice; a reversal there drops the effect to nothing instead of following it back.)
     *
     * The one thing layered on top is rest: a hinge that stops part-open lets the picture go, so a half-open phone is
     * never left dark, and [engage] eases that hand-over both ways.
     */
    private fun followHinge(now: Long): Float {
        val flatDeg = HingeTracker.FLAT_ENTER_DEG
        val angle = if (tracker.flat) 180f else HingeFeed.predicted() ?: tracker.visual ?: flatDeg
        val byAngle = ((flatDeg - angle) / (flatDeg - HINGE_BOUND_DARK_DEG)).coerceIn(0f, 1f)
        if (engaged && (tracker.flat || now - movedAt > STALL_MS)) {
            engaged = false
            FoldTrace.event("released: ${if (tracker.flat) "reached flat" else "hinge resting at ${"%.0f".format(angle)}°"}")
        }
        val dt = (now - engageAt).coerceIn(0L, 64L).toFloat()
        engageAt = now
        engage = (engage + if (engaged) dt / ENGAGE_MS else -dt / UNFOLD_FADE_MS).coerceIn(0f, 1f)
        if (!engaged && engage == 0f) {
            // Nothing left to draw: close the episode books too, or [busy] would keep the frame loop spinning.
            litAt = -1L; flatAt = -1L; reopenedAt = -1L
            if (closedAt < 0 && closeStartAt >= 0) { closeStartAt = -1L; peak = tracker.raw ?: 0f }
        }
        return START_M_ON_UNFOLD * byAngle * easeInOutSine(engage)
    }

    fun targetM(now: Long): Float = when {
        waitingForPanel -> if (expanded) START_M_ON_UNFOLD else COVER_START_M

        // Fold8Duo: a real angle is connected — follow the hinge, in both directions, rather than the clock.
        followsHinge -> followHinge(now)

        // Unfold, like iPhone Duo: the rotating (left) half fades from dark and blurred to clear. Recording the
        // Fold8 showed the panel lights only as the hinge is nearly flat, so a hinge-bound fade was over before it
        // was visible. The fade is a steady, fixed-length ease from the moment the panel is lit instead.
        expanded && litAt >= 0 -> {
            val t = ((now - litAt) / UNFOLD_FADE_MS).coerceIn(0f, 1f)
            if (t >= 1f) { litAt = -1L; flatAt = -1L; 0f }
            else {
                val timed = START_M_ON_UNFOLD * (1f - easeInOutSine(t))
                // With a continuous sensor, a hand that's already flat clears sooner; the timed fade still caps it,
                // so holding the phone half open never leaves Home blurred.
                val flatDeg = HingeTracker.FLAT_ENTER_DEG
                val byAngle = (tracker.visual ?: flatDeg).let { a -> if (litAngle >= flatDeg) 0f else ((flatDeg - a) / (flatDeg - litAngle)).coerceIn(0f, 1f) }
                if (continuous) minOf(timed, START_M_ON_UNFOLD * byAngle) else timed
            }
        }

        // Fold: blur builds with the hinge (continuous) or at the learned speed (stepped), complete at closed.
        expanded && closeStartAt >= 0 -> {
            val since = (now - closeStartAt).toFloat()
            val stalled = closedAt < 0 && (if (continuous) now - movedAt > STALL_MS else now - angleAt > predictedCloseMs + STALL_MS)
            when {
                stalled -> { closeStartAt = -1L; peak = tracker.raw ?: 0f; 0f } // deliberately half-open (flex mode): clear
                closedAt >= 0 && now - closedAt > CLOSED_STALL_MS -> { closeStartAt = -1L; closedAt = -1L; 0f } // never stuck dimmed
                closedAt >= 0 -> 1f
                continuous -> easeInOutSine(((HingeTracker.FLAT_ENTER_DEG - (HingeFeed.predicted() ?: tracker.visual ?: HingeTracker.FLAT_ENTER_DEG)) /
                    (HingeTracker.FLAT_ENTER_DEG - HingeTracker.CLOSED_ENTER_DEG)).coerceIn(0f, 1f)) * HOLD_M_BEFORE_CLOSED
                else -> easeInOutSine((since / predictedCloseMs).coerceIn(0f, 1f)) * HOLD_M_BEFORE_CLOSED
            }
        }

        // Reopened before closing: settle back.
        expanded && reopenedAt >= 0 -> { if (now - reopenedAt > FINISH_MS * 2) reopenedAt = -1L; 0f }

        // Opening from the cover: quick whole-screen blur until the inner display takes over.
        !expanded && coverOpeningAt >= 0 -> {
            // Fold8Duo: the swap the engine promised is overdue, or nothing came at all: the opening was abandoned and
            // the front screen is staying — give it back, rather than hold it dark until a long stall timer.
            val since = (now - coverOpeningAt).toFloat()
            val due = if (coverSweepMs > 0f) coverSweepMs + COVER_SWEEP_LEAD_MS + COVER_SWAP_GRACE_MS else COVER_OPEN_STALL_MS.toFloat()
            if (since > due) { coverOpeningAt = -1L; FoldTrace.event("cover opening abandoned: no swap after ${since.toInt()} ms"); 0f }
            // Fold8Duo: the first ~20° of an opening are free — the inner screen cannot be seen yet — so the front
            // screen spends them handing over. The front rides the real angle from the hinge's first word to just
            // before the panels swap: steady instead of a blackout, and it goes back if the hand does. (A timed
            // ease-out put it most of the way across in 60 ms, which read as the screen simply going dark.)
            else if (HingeFeed.connected && coverSweepMs > 0f) easeInOutSine(((now - coverOpeningAt) / coverSweepMs).coerceIn(0f, 1f))
            else if (HingeFeed.connected) easeInOutSine((((HingeFeed.predicted() ?: tracker.visual ?: 0f) - COVER_SWEEP_FROM_DEG) /
                (COVER_SWEEP_TO_DEG - COVER_SWEEP_FROM_DEG)).coerceIn(0f, 1f))
            else if (continuous) easeOutCubic(((tracker.visual ?: 0f) / HingeTracker.HALFWAY_DEG).coerceIn(0f, 1f))
            else easeOutCubic(((now - coverOpeningAt) / COVER_OPEN_MS).coerceIn(0f, 1f))
        }

        // Cover after folding: short focus-in.
        !expanded && coverLitAt >= 0 -> {
            val since = (now - coverLitAt).toFloat()
            val t = if (HingeFeed.connected && coverLitAngle > COVER_REVEAL_DONE_DEG + 6f) {
                // Fold8Duo (early cover): the front screen lit while the hinge still had travel left, so the come-back
                // rides the rest of the fold and lands as the phone shuts. Bounded both ways: never quicker than a calm
                // minimum (a snap-shut would flash it), never slower than a ceiling (the HAL can fall silent once the
                // system believes the phone is closed, and a hinge held ajar must not leave the front screen dark).
                val angle = if (tracker.closed) 0f else HingeFeed.predicted() ?: 0f
                val byAngle = ((coverLitAngle - angle) / (coverLitAngle - COVER_REVEAL_DONE_DEG)).coerceIn(0f, 1f)
                // Fold8Duo: a hinge that has fallen silent is shut, or the HAL has stopped (the front screen can light
                // after the close, with no travel left to ride): from then on a timed reveal runs as the floor.
                val silence = (now - angleAt - COVER_LIT_SILENCE_MS).coerceAtLeast(0L) / COVER_REVEAL_MS
                maxOf(byAngle, since / COVER_REVEAL_SLOWEST_MS, silence).coerceAtMost(since / COVER_REVEAL_FASTEST_MS).coerceIn(0f, 1f)
            } else (since / COVER_REVEAL_MS).coerceIn(0f, 1f)
            if (t >= 1f) { coverLitAt = -1L; 0f } else COVER_START_M * (1f - (if (FOLD_FULL_WIDTH_SWEEP) easeInOutSine(t) else easeOutCubic(t)))
        }
        else -> 0f
    }

    private fun learnOpen(ms: Float) {
        predictedOpenMs = (predictedOpenMs * .7f + ms.coerceIn(200f, 1400f) * .3f)
        prefs.edit().putFloat("fold_open_ms", predictedOpenMs).apply()
    }

    private fun learnClose(ms: Float) {
        predictedCloseMs = (predictedCloseMs * .7f + ms.coerceIn(250f, 1800f) * .3f)
        prefs.edit().putFloat("fold_close_ms", predictedCloseMs).apply()
    }
}
private fun easeOutCubic(t: Float): Float { val u = 1f - t; return 1f - u * u * u }
private fun easeInOutSine(t: Float): Float = (-(kotlin.math.cos(Math.PI * t) - 1) / 2).toFloat()

@RequiresApi(33)
private class DuoShader {
    private val shader = RuntimeShader(SOURCE)

    fun effect(width: Float, height: Float, m: Float, cover: Boolean, geometry: FoldGeometry,
        fullWidth: Boolean = false, overlay: Boolean = false): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("fullWidth", if (fullWidth) 1f else 0f)
        shader.setFloatUniform("overlay", if (overlay) 1f else 0f)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("axis", if (geometry.horizontal) 1f else 0f)
        shader.setFloatUniform("hingePos", geometry.hingePx)
        shader.setFloatUniform("side", if (geometry.movingAfterHinge) 1f else -1f)
        shader.setFloatUniform("m", m)
        // 72px on a 1600px-wide canvas in the recreation ≈ 4.5% of width; the cover uses a light version.
        shader.setFloatUniform("maxRadius", width * .045f)
        shader.setFloatUniform("cover", if (cover) 1f else 0f)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

    companion object {
        private const val SOURCE = """
            uniform shader content;
            uniform float2 size;
            uniform float m;
            uniform float maxRadius;
            uniform float cover;
            uniform float axis;     // 0: the hinge runs top to bottom (compare x); 1: side to side (compare y)
            uniform float hingePos; // the hinge along that axis
            uniform float side;     // -1: the moving half (or the cover's hinge edge) is before it; +1: after it
            uniform float fullWidth; // Fold8Duo: 1 = the dark front crosses the whole inner screen, not just the moving half
            uniform float overlay;   // Fold8Duo: 1 = drawn at half size over the sharp screen (FoldSmoothness)

            // 24-tap disk (3 rings) keeps large radii smooth.
            half4 blur(float2 p, float r) {
                if (r < 0.75) return content.eval(p);
                float a = r * 0.33; float b = r * 0.66; float c = r;
                float a7 = a * 0.7071; float b7 = b * 0.7071; float c7 = c * 0.7071;
                half4 sum = content.eval(p) * 0.08;
                sum += (content.eval(p + float2(a, 0.0)) + content.eval(p + float2(-a, 0.0)) + content.eval(p + float2(0.0, a)) + content.eval(p + float2(0.0, -a))
                      + content.eval(p + float2(a7, a7)) + content.eval(p + float2(-a7, a7)) + content.eval(p + float2(a7, -a7)) + content.eval(p + float2(-a7, -a7))) * 0.05;
                sum += (content.eval(p + float2(b, 0.0)) + content.eval(p + float2(-b, 0.0)) + content.eval(p + float2(0.0, b)) + content.eval(p + float2(0.0, -b))
                      + content.eval(p + float2(b7, b7)) + content.eval(p + float2(-b7, b7)) + content.eval(p + float2(b7, -b7)) + content.eval(p + float2(-b7, -b7))) * 0.04;
                sum += (content.eval(p + float2(c, 0.0)) + content.eval(p + float2(-c, 0.0)) + content.eval(p + float2(0.0, c)) + content.eval(p + float2(0.0, -c))
                      + content.eval(p + float2(c7, c7)) + content.eval(p + float2(-c7, c7)) + content.eval(p + float2(c7, -c7)) + content.eval(p + float2(-c7, -c7))) * 0.025;
                return sum;
            }

            // Fold8Duo: nine taps. Under the dark front fine blur detail is invisible, and the full-width sweep puts
            // most of a 4.5-megapixel screen there; 25 taps everywhere cost a median 11 ms against an 8.3 ms frame.
            half4 blurLite(float2 p, float r) {
                if (r < 0.75) return content.eval(p);
                float d = r * 0.6; float d7 = d * 0.7071;
                half4 sum = content.eval(p) * 0.2;
                sum += (content.eval(p + float2(d, 0.0)) + content.eval(p + float2(-d, 0.0)) + content.eval(p + float2(0.0, d)) + content.eval(p + float2(0.0, -d))
                      + content.eval(p + float2(d7, d7)) + content.eval(p + float2(-d7, d7)) + content.eval(p + float2(d7, -d7)) + content.eval(p + float2(-d7, -d7))) * 0.1;
                return sum;
            }

            // Fold8Duo: one soft dark front that travels across the screen. u runs along its path: the dark enters at
            // u = 0 and has covered everything once it passes u = 1. mc is the clamped strength, mRaw carries intensity.
            half4 sweepFront(float2 p, float u, float mc, float mRaw, float glintGain) {
                float w = 0.35;                                  // softness of the front, as a share of the path
                float f = pow(mc, 1.4) * (1.0 + w);              // where the front is; past 1 = fully dark
                float dark = 1.0 - smoothstep(f - w, f, u);
                float frost = 1.0 - smoothstep(f - w, f + 0.5 * w, u);   // the frost runs a little ahead of the dark
                float rf = maxRadius * frost * (0.35 + 0.65 * mc) * max(1.0, mRaw);
                half4 cf;
                if (dark > 0.8) { cf = blurLite(p, rf); } else { cf = blur(p, rf); }
                float bandF = (u - f) / 0.07;                    // a glint riding the front, brightest mid-way
                float glint = exp(-bandF * bandF) * glintGain * mc * (1.0 - mc);
                if (overlay > 0.5) {
                    // Fold8Duo, half-resolution mode: this layer is drawn at half size OVER the sharp screen, so it only
                    // supplies the share of the picture that is frosted or dark (none of the clear part, which stays
                    // full-resolution underneath), plus the glint at full strength, added as light.
                    float share = max(frost, dark);
                    return half4(cf.rgb * (1.0 - 0.9 * dark) * share + half3(glint) * cf.a, cf.a * share);
                }
                return half4(cf.rgb * (1.0 - 0.9 * dark) + half3(glint) * cf.a, cf.a);
            }

            half4 main(float2 p) {
                float mc = clamp(m, 0.0, 1.0);
                float mm = mc * mc * (3.0 - 2.0 * mc) * max(1.0, m); // smoothstep (as in the recreation), scaled by intensity
                float coord = axis < 0.5 ? p.x : p.y;
                float extent = axis < 0.5 ? size.x : size.y;
                if (cover > 0.5 && fullWidth > 0.5) {
                    // Fold8Duo: the front screen hands over to the inside. The dark comes in from the free edge and
                    // runs to the hinge — right to left on a Fold held naturally — the way the picture would go if
                    // it were being pulled round to the back. Unfolding back onto the cover plays it in reverse.
                    float toHinge = clamp(side < 0.0 ? (extent - coord) / extent : coord / extent, 0.0, 1.0); // 0 = free edge
                    return sweepFront(p, toHinge, mc, m, 0.0);
                }
                if (cover > 0.5) {
                    // Outer screen, as on iPhone Duo: blur and darkness grow away from the hinge edge
                    // toward the free edge. Same curves as the inner half.
                    float eo = clamp(side < 0.0 ? coord / extent : (extent - coord) / extent, 0.0, 1.0);
                    half4 co = blur(p, maxRadius * mm * pow(eo, 1.35));
                    float dO = clamp((eo - 0.2) / 0.8, 0.0, 1.0);
                    float ko = 1.0 - min(1.0, 2.0 * mm * pow(dO, 1.35));
                    return half4(co.rgb * ko, co.a);
                }
                if (fullWidth > 0.5) {
                    // Fold8Duo: with the inner panel lit from ~20° (early light) a half that is simply "on" reads as a
                    // pop, and a fold that darkens only up to the hinge never finishes. So the whole screen emerges
                    // from the dark: one soft front crosses the full width. Opening, it leaves from the still half's
                    // far edge and the moving half's outer edge clears last; folding, it comes back the same way and
                    // ends with everything dark. u: 0 = the moving half's outer edge, 1 = the still half's far edge.
                    float u = clamp(side < 0.0 ? coord / extent : (extent - coord) / extent, 0.0, 1.0);
                    return sweepFront(p, u, mc, m, 0.3);
                }
                // Inner screen: the half with the cover behind it stays sharp; the moving half is blurred and
                // darkened toward its outer edge.
                float e;
                if (side < 0.0) {
                    if (coord >= hingePos) return content.eval(p);
                    e = clamp((hingePos - coord) / max(hingePos, 1.0), 0.0, 1.0);
                } else {
                    if (coord <= hingePos) return content.eval(p);
                    e = clamp((coord - hingePos) / max(extent - hingePos, 1.0), 0.0, 1.0);
                }
                half4 c = blur(p, maxRadius * mm * pow(e, 1.35));
                float d = clamp((e - 0.2) / 0.8, 0.0, 1.0);
                float k = 1.0 - min(1.0, 2.0 * mm * pow(d, 1.35));
                // A soft band of light that leaves the hinge and crosses the half as it clears, brightest mid-way
                // (idea from FoldFX).
                float band = (e - (1.0 - mc)) / 0.1;
                float sweep = exp(-band * band) * 0.55 * mc * (1.0 - mc);
                return half4(c.rgb * k + half3(sweep) * c.a, c.a);
            }
        """
    }
}

/** Share of the morph during which the still picture stays fully visible. */
private const val STILL_HOLD = .55f
private const val MORPH_UNFOLD_MS = 650f
private const val MORPH_FOLD_MS = 420f
/** Inner panel lights around 120–135° on Z Fold: the cover half is still ~50° from flat. */
private const val START_M_ON_UNFOLD = 1f
/** Unfold fade length (the iPhone Duo reveal reads as ~half a second). */
private const val UNFOLD_FADE_MS = 520f
/** Strongest whole-screen dim while folding. */
private const val FOLD_DIM = .6f
private const val HOLD_M_BEFORE_CLOSED = .9f
// Measured on a Galaxy Z Fold8: the hinge reports only 0/90/180° on a 200 ms grid, so "flat" can arrive up to
// 200 ms after the panel is really flat. Finish faster once it does so the reveal doesn't trail the hand.
private const val FINISH_MS = 120f
private const val STALL_MS = 900f
private const val COVER_MS = 380f
/** The cover lights right at closed, where the Duo outer screen is nearly clean: a light settle. */
private const val START_M_ON_COVER = .7f
private const val COVER_OPEN_MS = 220f
// Fold8Duo: an opening with no swap signal is abandoned after this (was 2 s; the swap normally follows TENT by ~400 ms).
private const val COVER_OPEN_STALL_MS = 1_200L
// Fold8Duo: how long past the engine's promised swap the front screen waits before it is given back.
private const val COVER_SWAP_GRACE_MS = 400f
// Fold8Duo: no hinge word for this long after the front screen lit means the hinge is shut or the HAL has stopped.
private const val COVER_LIT_SILENCE_MS = 250L
private const val FOLLOW_MS = 28f
private const val MIN_FOLD_DROP_DEG = 20f
private const val CLOSED_STALL_MS = 1_800L
private const val LIT_TIMEOUT_MS = 1_200L
private const val IDLE_POLL_MS = 50L
/** How much smaller the open screen is at the start of the reveal (97%). */
private const val FOLD_SCALE = .03f
private const val MOVE_DEG = 3f
private const val REOPEN_DEG = 15f
private const val CAPABILITY_KEY = "fold_hinge_capability"
// Fold8Duo: the inner screen's fold effect crosses the whole width (starts dark, ends dark) instead of stopping at
// the hinge. Owner preference, 2026-09-19; TODO make it a setting beside Fold Effect before offering it upstream.
private const val FOLD_FULL_WIDTH_SWEEP = true
// Fold8Duo: a fold ends with the open screen fully dark, so the cover picks up from fully dark and the same front runs
// back the other way (hinge to free edge) — the phone "comes back" instead of cutting to a half-dimmed Home.
private val COVER_START_M get() = if (FOLD_FULL_WIDTH_SWEEP) 1f else START_M_ON_COVER
private val COVER_REVEAL_MS get() = if (FOLD_FULL_WIDTH_SWEEP) 650f else COVER_MS
// Fold8Duo: the front screen's sweep ends this long before the expected panel swap, and stays within sane bounds.
private const val COVER_SWEEP_LEAD_MS = 50
private const val COVER_SWEEP_MIN_MS = 220f
private const val COVER_SWEEP_MAX_MS = 900f
private const val DIRECTION_MIN_DEG = 3f
// Fold8Duo: the come-back on the front screen is complete by this hinge angle, and takes between these two times.
private const val COVER_REVEAL_DONE_DEG = 4f
private const val COVER_REVEAL_FASTEST_MS = 480f
private const val COVER_REVEAL_SLOWEST_MS = 1_100f
// Fold8Duo: following the hinge, the open screen is fully dark from here down (the panels swap at ~5°).
private const val HINGE_BOUND_DARK_DEG = 12f
// Fold8Duo: how fast the picture picks the hinge back up after a rest.
private const val ENGAGE_MS = 140f
// Fold8Duo: a drop of this much from a rest is folding, not jitter (the HAL's words are ~10° apart anyway).
private const val REENGAGE_DROP_DEG = 4f
// Fold8Duo: how close to 0° / 180° a public-sensor reading must be to count as an authoritative end.
private const val ENDS_TOLERANCE_DEG = 1f
// Fold8Duo: the front screen's right-to-left hand-over runs across this stretch of the real hinge angle. The HAL's
// first word is 6–13°; early light (with its swap delay) changes panels at roughly 18–25°.
// Measured: with a 150 ms swap delay the front screen had 274 ms and the hinge had only reached 12°, so a sweep
// running to 18° was cut off half-way (m = 0.51) — it read as abrupt. It now finishes at the angle a hand really
// reaches by the swap.
private const val COVER_SWEEP_FROM_DEG = 5f
private const val COVER_SWEEP_TO_DEG = 14f
// Fold8Duo: the HAL's first word on an opening is 6–13°; anything rising from here up is the hinge moving.
private const val FEED_OPENING_DEG = 5f
// Fold8Duo: pixels per mm at Compose density 1 (160 dpi); the iPhone Duo pane's numbers are in mm.
private const val PX_PER_MM_AT_DENSITY_1 = 160f / 25.4f
// Fold8Duo: the Settings preview stands for the whole open screen: each of its two pages is one 74 mm half.
private const val PREVIEW_HALF_MM = 74f

/**
 * The fold effect on a preview (Settings): [m] 0 is open and clear, 1 half folded. Same shader, sweep and scale as Home,
 * with the hinge down the middle and the left half moving. Fold8Duo: previews whichever style is chosen; [intensity] is
 * the Intensity setting (the sweep folds it into its strength, the Duo pane into its blur and dark).
 */
@Composable
internal fun Modifier.foldPreviewEffect(m: () -> Float, intensity: () -> Float = { 1f }): Modifier {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) DuoShader() else null }
    val duoShader = remember { if (Build.VERSION.SDK_INT >= 33) DuoPaneShader() else null }
    FoldStyles.load(LocalContext.current)
    val duo = FoldStyles.current.value == FoldStyle.DUO
    return graphicsLayer {
        val value = m().coerceIn(0f, 1f)
        val settle = if (duo) 1f else 1f - FOLD_SCALE * value
        scaleX = settle; scaleY = settle
        if (Build.VERSION.SDK_INT >= 33) renderEffect = when {
            value <= 0f -> null
            duo && duoShader != null -> duoShader.effect(size.width, size.height, DuoFrame(value * 90f, 0f, 0f), cover = false,
                geometry = FoldGeometry(false, size.width / 2f, false), pxPerMm = size.width / (2f * PREVIEW_HALF_MM), intensity = intensity())
            shader != null -> shader.effect(size.width, size.height, value * intensity(), cover = false, geometry = FoldGeometry(false, size.width / 2f, false))
            else -> null
        }
    }.then(if (shader == null) Modifier.drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = m().coerceIn(0f, 1f)), .5f to Color.Transparent, startX = 0f, endX = size.width))
    } else Modifier)
}

/** The fold effect's layout: which way the hinge runs, where it is, and which side of it moves. */
internal data class FoldGeometry(val horizontal: Boolean, val hingePx: Float, val movingAfterHinge: Boolean)

/**
 * In the natural orientation (unfolded landscape; cover portrait) the moving half, or the cover's hinge edge, is
 * on the left. Display rotation moves that edge: 90° to the bottom, 180° to the right, 270° to the top. A real
 * hinge from WindowManager gives the exact position; otherwise it's the middle.
 */
internal fun foldGeometry(rotation: Int, hinge: Hinge?, width: Float, height: Float): FoldGeometry {
    val horizontal = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270
    val after = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_180
    val middle = if (horizontal) height / 2f else width / 2f
    val position = hinge?.takeIf { it.vertical != horizontal }?.let { (it.startPx + it.endPx) / 2f } ?: middle
    return FoldGeometry(horizontal, position, after)
}

/**
 * Fold8Duo: this file's tuning, for the over-app fold overlay (FoldOverlay.kt), which runs the same [FoldTimeline] on a
 * different canvas. Read from here rather than copied, so the effect over apps cannot drift from the one on Home.
 */
/**
 * Fold8Duo: how Home draws its fold effect. The accepted renderer runs the shader over the whole screen at full
 * resolution (measured on the SM-F971U1: ~8.6% late frames, p50 12 ms against an 8.3 ms frame). Half resolution draws
 * the screen sharp and only the frosted and dark share of the effect at half size on top. Off until the owner has
 * compared the two by eye (Settings > Fold & Displays).
 */
internal object FoldSmoothness {
    private const val PREFS = "folio"
    private const val KEY = "fold8duo.foldHalfRes"
    val halfRes = mutableStateOf(false)
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        halfRes.value = context.getSharedPreferences(PREFS, 0).getBoolean(KEY, false)
    }

    fun set(context: Context, on: Boolean) {
        halfRes.value = on
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, on).apply()
    }
}

internal object FoldTuning {
    val followMs get() = FOLLOW_MS
    val startMOnUnfold get() = START_M_ON_UNFOLD
    val coverStartM get() = COVER_START_M
    val litTimeoutMs get() = LIT_TIMEOUT_MS
}
