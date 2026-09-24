package com.mccal.folio

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.mccal.folio.morph.MorphPlanner
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Fold8Duo: an app opening out of its icon (IconMorph, SPEC §4.7, WP-31). Off until the owner has seen it:
 * Settings › Fold & Displays › "Apps grow out of their icons".
 *
 * Android gives a third-party Home one way to animate a launch — hand the system a start rect and watch its stock
 * zoom. iOS grows a rounded card out of the icon, showing the app as it was last seen. To get that here:
 *
 *  1. The launch carries a custom animation that does nothing visible: the app stays unseen and Home stays put for
 *     [HOLD_MS]. The app starts at full speed underneath; only its picture waits.
 *  2. Meanwhile a card grows from the icon to the whole screen on [OverlayStage], above Home, on a spring. Home really is
 *     still there around it, zooming toward the icon as before ([IconMorph.homeZoom]).
 *  3. The card shows the icon giving way to the system's own last picture of that app (what Recents shows), fetched by
 *     the Shizuku engine ([FoldShizuku.taskSnapshot]). No engine, no picture, or a picture from the other screen: the
 *     icon on a plain ground instead, which is what Android's splash screen is about to show anyway.
 *  4. When the hold ends the real app is underneath, and the card fades away over it.
 *
 * The display mirror that frosts the fold effect cannot supply the picture: a hidden app is not on the screen to mirror.
 *
 * Best effort: anything missing (no stage, a fold in progress, animations off) returns null and the launch gets the
 * scale-up it always had. A launch that never takes Home away makes the card go back into the icon.
 */
internal object OpenMorph {
    private const val TAG = "FolioMorph"
    private const val PREFS = "folio"
    private const val KEY_ENABLED = "fold8duo_open_morph"

    /** How long the launch animation keeps the app unseen. MUST match res/anim/fold8duo_open_hold_*.xml. */
    private const val HOLD_MS = 420L
    /**
     * The card fades once Home has actually been covered — its lifecycle drops below STARTED when the app's window is
     * fully in front, which is the end of the hold (or of the system's own transition, if One UI ignored the hold) —
     * never before the hold itself is over, and at the latest at [COLD_START_MAX_MS] (SPEC coldStart.maxHoldMs: a cold
     * start may take that long to draw; then fade regardless). WP-53: was a blind 540 ms timer.
     */
    private const val COLD_START_MAX_MS = MotionTokens.COLD_START_MAX_MS
    private const val FADE_MS = MotionTokens.HANDOFF_MS
    private const val GIVE_UP_AT_MS = 320L
    private const val RETURN_MS = 240L
    private const val SPRING_RESPONSE = MotionTokens.OPEN_RESPONSE_S
    private const val SPRING_DAMPING = MotionTokens.OPEN_DAMPING

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "FolioMorphIo").apply { isDaemon = true } } /* a thread name */ // english-only

    /** On by default since WP-53 (2026-09-22): the owner asked for the iOS open; it is judged on real launches now. */
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, on: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, on).apply() }

    /**
     * The options to launch with, or null to leave this launch to the system's scale-up. [icon] is in [source]'s window.
     * [app] is the app being launched when the caller knows it (WP-53: every launch site does); otherwise the tile Home
     * last laid out at [icon] is looked up, which only Home's own grid and dock register.
     */
    fun begin(activity: ComponentActivity, source: View, icon: Rect, app: AppEntry? = null): Bundle? {
        if (!enabled(activity) || !ValueAnimator.areAnimatorsEnabled() || icon.isEmpty) return null
        val stage = OverlayStage.get() ?: return null
        if (stage.showing != null) return null                                   // a fold outranks a launch
        val app = app ?: appAt(icon)
        val origin = IntArray(2).also(source::getLocationOnScreen)
        val onScreen = MorphPlanner.Box(icon.left + origin[0], icon.top + origin[1], icon.right + origin[0], icon.bottom + origin[1])
        val scene = Scene(activity, onScreen, app?.icon, night(activity), screenRadius(activity), launches = true)
        if (!stage.show(scene)) return null
        if (app != null && FoldShizuku.connected) io.execute {
            val asked = SystemClock.uptimeMillis()
            val buffer = FoldShizuku.taskSnapshot(app.packageName, app.user.hashCode())     // UserHandle's hash is its id
            val picture = buffer?.let { runCatching { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }.getOrNull() }
            buffer?.close()
            Log.i(TAG, "opening ${app.packageName}: last picture ${picture?.let { "${it.width}x${it.height}" } ?: "none"} after ${SystemClock.uptimeMillis() - asked} ms (${FoldShizuku.taskSnapshotRoute()})")
            if (picture != null) main.post { scene.picture = picture }
        }
        return ActivityOptions.makeCustomAnimation(activity, R.anim.fold8duo_open_hold_in, R.anim.fold8duo_open_hold_out).toBundle()
    }

    /** The app whose tile Home last laid out at [icon]: that is where its picture and its package come from. */
    private fun appAt(icon: Rect): AppEntry? {
        val apps = FolioSettingsBridge.liveModel?.get()?.state?.value?.apps ?: return null
        return apps.firstOrNull { app -> IconBounds.of(app.id)?.let { abs(it.left - icon.left) <= 2 && abs(it.top - icon.top) <= 2 && abs(it.width() - icon.width()) <= 2 } == true }
    }

    private fun night(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun screenRadius(context: Context): Float = runCatching {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
            .getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
    }.getOrNull() ?: 0f

    /** Development builds: the card from a made-up icon, with nothing launched, so the drawing can be checked alone. */
    fun demo(service: AccessibilityService, stage: OverlayStage, returns: Boolean, stayInScreenshots: Boolean = false, capture: String? = null) {
        stage.debugStayInScreenshots = stayInScreenshots
        if (capture != null) for ((at, ms) in listOf("a" to 70L, "b" to 170L, "c" to 300L)) main.postDelayed({ stage.capture("$capture-$at") }, ms + 80)
        val size = stage.screenSize()
        val side = (size.x * .14f).toInt()
        val left = (size.x * .62f).toInt(); val top = (size.y * .66f).toInt()
        val tile = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(0xFF2F7CF6.toInt()) }
        stage.show(Scene(null, MorphPlanner.Box(left, top, left + side, top + side), tile, night(service), screenRadius(service), launches = !returns))
    }

    /** Development (probe P-10): asks the engine for an app's last picture and says what came back. */
    fun probeSnapshot(packageName: String) {
        io.execute {
            val asked = SystemClock.uptimeMillis()
            val buffer = FoldShizuku.taskSnapshot(packageName, 0)
            val took = SystemClock.uptimeMillis() - asked
            val usable = buffer?.let { runCatching { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }.getOrNull() }
            Log.i(OverlayStage.TAG, "snapshot probe: $packageName -> ${buffer?.let { "${it.width}x${it.height} format=${it.format} usage=0x${java.lang.Long.toHexString(it.usage)}" } ?: "null"}, " +
                "as a bitmap: ${usable?.let { "${it.width}x${it.height} ${it.config}" } ?: "no"}, $took ms, route: ${FoldShizuku.taskSnapshotRoute()}") /* logcat */ // english-only
            buffer?.close()
        }
    }

    private class Scene(private val home: ComponentActivity?, private val icon: MorphPlanner.Box, private val tile: Bitmap?,
        private val night: Boolean, private val screenRadius: Float, private val launches: Boolean) : OverlayStage.Scene {
        override val name = "open morph"
        override val renderScale = 1f                   // a card has an edge; half resolution would show on it
        override val maxMs = COLD_START_MAX_MS + FADE_MS + 200L

        /** The app as it was last seen, when the engine had one. Main thread. */
        var picture: Bitmap? = null
        private var pictureAt = 0L
        /** The launch's clock: the hold and the hand-over are measured from the moment the app was started. */
        private val startedAt = SystemClock.uptimeMillis()
        /** The card's clock: the stage needs a few frames to get a surface, and the card must not appear part-grown. */
        private var drawnAt = 0L
        private var returningAt = 0L
        /** When the hand-over began: Home covered, or the cold-start cap reached. */
        private var fadeAt = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip = Path()
        private val tileClip = Path()
        private val card = RectF()
        private val from = Rect()
        private val to = RectF()

        override fun draw(canvas: Canvas, width: Int, height: Int, nowMs: Long, live: LiveFrame?): Boolean {
            val t = nowMs - startedAt
            // Home still in front a third of a second in: the launch was refused, or opened somewhere that left Home
            // where it was (a pop-up, a split). The card has nothing to become, so it goes back where it came from.
            if (returningAt == 0L && t >= GIVE_UP_AT_MS && (!launches || home?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)) {
                returningAt = nowMs
                if (launches) Log.i(TAG, "open morph: Home is still in front after $t ms; the card goes back into its icon")
            }
            if (drawnAt == 0L) { drawnAt = nowMs; Log.i(TAG, "open morph: first frame ${nowMs - startedAt} ms after the tap") }
            val out = MorphPlanner.spring((nowMs - drawnAt) / 1000f, SPRING_RESPONSE, SPRING_DAMPING)
            val progress = if (returningAt == 0L) out else {
                val back = ((nowMs - returningAt) / RETURN_MS.toFloat()).coerceIn(0f, 1f)
                if (back >= 1f) return false
                out * (1f - back * back * (3f - 2f * back))
            }
            // The hand-over: Home is covered (its lifecycle fell below STARTED), or the cold-start cap is reached.
            if (fadeAt == 0L && returningAt == 0L && t >= HOLD_MS) {
                val covered = launches && home?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false
                if (covered || t >= COLD_START_MAX_MS || !launches) {
                    fadeAt = nowMs
                    if (launches) Log.i(TAG, "open morph: hand-over ${if (covered) "Home covered" else "cold-start cap"} $t ms after the tap")
                }
            }
            val fade = if (returningAt != 0L || fadeAt == 0L) 1f else 1f - ((nowMs - fadeAt) / FADE_MS.toFloat())
            if (fade <= 0f) return false

            val box = MorphPlanner.card(icon, width, height, progress)
            card.set(box.left, box.top, box.right, box.bottom)
            val radius = MorphPlanner.cornerRadius(minOf(icon.width, icon.height).toFloat(), screenRadius, progress)
            clip.rewind(); clip.addRoundRect(card, radius, radius, Path.Direction.CW)
            val layer = canvas.saveLayerAlpha(card, (fade * 255).toInt().coerceIn(0, 255))
            canvas.clipPath(clip)
            canvas.drawColor(if (night) GROUND_NIGHT else GROUND_DAY)

            val shot = picture?.takeIf { sameShape(it, width, height) }
            if (shot != null) {
                if (pictureAt == 0L) pictureAt = nowMs
                // Eased in over a few frames even if it arrives late, so it is never a cut.
                paint.alpha = (255 * minOf(1f - MorphPlanner.iconAlpha(progress), (nowMs - pictureAt) / PICTURE_IN_MS)).toInt().coerceIn(0, 255)
                from.set(0, 0, shot.width, shot.height)
                canvas.drawBitmap(shot, from, card, paint)
            }
            tile?.let {
                // The icon keeps its own shape as the card widens, and makes way for the app. With no picture to make
                // way for it stays, on the plain ground, and lands where the system's splash screen will put it.
                val grow = 1f + .35f * progress
                val side = minOf(icon.width, icon.height) * grow
                to.set(card.centerX() - side / 2f, card.centerY() - side / 2f, card.centerX() + side / 2f, card.centerY() + side / 2f)
                paint.alpha = if (shot != null) (255 * MorphPlanner.iconAlpha(progress)).toInt() else 255
                from.set(0, 0, it.width, it.height)
                if (paint.alpha > 0) {
                    // Folio's tiles are iOS-shaped; the app's own bitmap may be a full square.
                    val save = canvas.save()
                    tileClip.rewind(); tileClip.addRoundRect(to, .225f * side, .225f * side, Path.Direction.CW)
                    canvas.clipPath(tileClip)
                    canvas.drawBitmap(it, from, to, paint)
                    canvas.restoreToCount(save)
                }
            }
            canvas.restoreToCount(layer)
            return true
        }

        /** A picture taken on the other screen is the wrong shape for this one; stretching it would look broken. */
        private fun sameShape(shot: Bitmap, width: Int, height: Int): Boolean {
            if (shot.width <= 0 || shot.height <= 0 || height <= 0) return false
            val a = shot.width.toFloat() / shot.height
            val b = width.toFloat() / height
            return abs(a - b) / b < .06f
        }

        private companion object {
            const val GROUND_DAY = 0xFFFAFAFA.toInt()
            const val GROUND_NIGHT = 0xFF121212.toInt()
            const val PICTURE_IN_MS = 90f
        }
    }
}
