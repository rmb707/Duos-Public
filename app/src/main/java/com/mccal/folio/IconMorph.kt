package com.mccal.folio

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcelable
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.mccal.folio.morph.MorphPlanner
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Fold8Duo: iOS-style app open and close (IconMorph, SPEC §4.7).
 *
 * **Close.** One UI's gesture navigation speaks AOSP Launcher3's GestureNavContract to a third-party Home: on the
 * SM-F971U1 every swipe home starts Home "(has extras)" from com.sec.android.app.launcher. The extras carry the closing
 * app's component, its user and a Message to answer. Home answers with the rect of that app's icon and the system flies
 * the app's window into it, instead of into the middle of the screen. No privilege needed; it is the handshake Pixel's
 * own launcher uses. Home keeps answering for a moment, so an icon that moves while Home comes back is followed.
 *
 * **Open.** Stock Android's scale-up for a third-party Home starts from the icon's square and squashes the app on the
 * way up, and Home just sits there. Here the start keeps the window's shape ([MorphPlanner.evenScaleStart]) and Home
 * zooms toward the icon as the app grows out of it, then settles back when you return, the way iOS moves its Home.
 *
 * Best effort throughout: any failure leaves the system's own animation, never a stuck Home.
 */
internal object IconMorph {
    private const val TAG = "FolioMorph"
    private const val PREFS = "folio"
    private const val KEY_ENABLED = "fold8duo_icon_morph"

    // AOSP Launcher3, src/com/android/launcher3/GestureNavContract.java.
    private const val EXTRA_GESTURE_CONTRACT = "gesture_nav_contract_v1"
    private const val EXTRA_ICON_POSITION = "gesture_nav_contract_icon_position"
    private const val EXTRA_REMOTE_CALLBACK = "android.intent.extra.REMOTE_CALLBACK"
    private const val EXTRA_ON_FINISH_CALLBACK = "gesture_nav_contract_finish_callback"

    /** How long Home keeps answering after a swipe home: long enough for the pager to come back to a Home page. */
    private const val FOLLOW_MS = 900L

    /** How far Home zooms toward the icon while an app opens out of it. */
    private const val ZOOM_SCALE = .1f
    private const val ZOOM_OUT_MS = 380L
    private const val ZOOM_BACK_MS = 460L
    /** A launch that leaves Home on screen (refused, or opened in a pop-up) must not leave it zoomed. */
    private const val WATCHDOG_MS = 700L

    private val main = Handler(Looper.getMainLooper())

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, on: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, on).apply() }

    // ---------------------------------------------------------------------------------------------- where Home is

    @Volatile private var settledPage = 0
    @Volatile private var lastHomePage = 0

    /** From the launcher's pager each time it comes to rest. */
    fun onPageSettled(page: Int, homePages: Int) {
        settledPage = page
        if (page in 0 until homePages) lastHomePage = page
    }

    // ------------------------------------------------------------------------- close: answer the gesture contract

    private class Landing(val id: Int, val callback: Message, val component: ComponentName, val user: UserHandle, val startedAt: Long) {
        var sent: MorphPlanner.Box? = null
        var tile: String? = null
        var done = false
        fun age() = SystemClock.uptimeMillis() - startedAt
    }

    private var landing: Landing? = null
    private var landings = 0
    private val landed by lazy { Messenger(Handler(Looper.getMainLooper()) { msg -> onLanded(msg.what); true }) }

    /**
     * Call from onNewIntent, before anything else reads the intent. Settles a zoomed Home and answers a swipe home.
     * Returns the app the contract names, whose window is on its way home, or null: HomeAgain (WP-49) tells a return
     * from an app from a press on Home itself by it, since the contract is gone from the intent once answered.
     */
    fun onNewIntent(activity: ComponentActivity, intent: Intent?, state: () -> LauncherState): ComponentName? {
        springBack()
        val closing = runCatching { intent?.getBundleExtra(EXTRA_GESTURE_CONTRACT)?.parcel<ComponentName>(Intent.EXTRA_COMPONENT_NAME) }.getOrNull()
        if (intent != null) devLaunch(activity, intent, state)
        if (intent == null || !enabled(activity)) return closing
        runCatching { answer(activity, intent, state) }
            .onFailure { Log.w(TAG, "could not answer the swipe home; the system's own animation plays", it) }
        return closing
    }

    // ------------------------------------------------------------------------------- development: a launch by adb

    /** Set by Home: launches an app the way a tap on its icon does. Development builds only. */
    var devLauncher: ((AppEntry, Rect?) -> Unit)? = null

    /**
     * Development builds only:
     *
     *     adb shell am start -n com.mccal.folio.dev/com.mccal.folio.MainActivity --es fold8duo.launch <package>
     *
     * launches that app from its icon on Home, exactly as a tap would (the open morph included), so a launch can be
     * recorded with `screenrecord` without a hand on the phone. Home must be in front and unlocked.
     */
    private fun devLaunch(activity: ComponentActivity, intent: Intent, state: () -> LauncherState) {
        if (!activity.packageName.endsWith(".dev")) return
        val pkg = intent.getStringExtra(EXTRA_DEV_LAUNCH) ?: return
        intent.removeExtra(EXTRA_DEV_LAUNCH)
        val app = state().apps.firstOrNull { it.packageName == pkg && it.shortcutId == null } ?: run { Log.i(TAG, "dev launch: no app $pkg"); return }
        val bounds = IconBounds.of(app.id)
        Log.i(TAG, "dev launch: $pkg from ${bounds ?: "no icon on screen"}")
        main.post { devLauncher?.invoke(app, bounds) }
    }

    private const val EXTRA_DEV_LAUNCH = "fold8duo.launch"

    private fun answer(activity: ComponentActivity, intent: Intent, state: () -> LauncherState) {
        val contract = intent.getBundleExtra(EXTRA_GESTURE_CONTRACT) ?: return
        intent.removeExtra(EXTRA_GESTURE_CONTRACT)
        val component = contract.parcel<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        val user = contract.parcel<UserHandle>(Intent.EXTRA_USER)
        val callback = contract.parcel<Message>(EXTRA_REMOTE_CALLBACK)
        Log.i(TAG, "swipe home from ${component?.flattenToShortString()} (contract: ${contract.keySet().sorted()})")
        if (component == null || user == null || callback?.replyTo == null) { Log.w(TAG, "the contract is incomplete; not answering"); return }
        val now = Landing(++landings, callback, component, user, SystemClock.uptimeMillis())
        landing = now
        val frames = Choreographer.getInstance()
        val follow = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (landing !== now || now.done) return
                runCatching { aim(activity, now, state()) }.onFailure { Log.w(TAG, "lost the landing", it); return }
                if (now.age() < FOLLOW_MS) frames.postFrameCallback(this)
                else if (now.sent == null) Log.i(TAG, "no icon on screen for ${component.flattenToShortString()}; the system's own animation played")
            }
        }
        follow.doFrame(0L) // answer at once when the icon is already where Home will show it
    }

    private fun aim(activity: ComponentActivity, now: Landing, state: LauncherState) {
        val tile = tileFor(now, state) ?: return
        val icon = IconBounds.of(tile) ?: return
        val decor = activity.window?.decorView ?: return
        // After a fold or unfold Home has to be laid out for the new screen before its icon rects mean anything.
        val screenBounds = activity.windowManager.currentWindowMetrics.bounds
        if (abs(decor.width - screenBounds.width()) > 2 || abs(decor.height - screenBounds.height()) > 2) return
        val density = activity.resources.displayMetrics.density
        val box = MorphPlanner.Box(icon.left, icon.top, icon.right, icon.bottom)
        if (!MorphPlanner.wholeIcon(box, decor.width, decor.height, (24 * density).toInt(), (200 * density).toInt())) return
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        val onScreen = box.offset(origin[0], origin[1])
        val previous = now.sent
        // Resend only for a real move: the system restarts its spring toward every new rect (see MorphPlanner.realMove).
        if (previous != null && !MorphPlanner.realMove(previous, onScreen, density)) return
        send(now, onScreen)
        now.sent = onScreen
        now.tile = tile
        Log.i(TAG, "${if (previous == null) "landing" else "landing moved"}: ${now.component.flattenToShortString()} into $tile at " +
            "[${onScreen.left},${onScreen.top} ${onScreen.width}x${onScreen.height}], ${now.age()} ms after the swipe") /* logcat */ // english-only
    }

    private fun tileFor(now: Landing, state: LauncherState): String? {
        val exact = state.apps.filter { it.component == now.component && it.user == now.user }
        val candidates = exact.ifEmpty { state.apps.filter { !it.isShortcut && it.packageName == now.component.packageName && it.user == now.user } }
        if (candidates.isEmpty()) return null
        val page = MorphPlanner.pageAfterReturn(settledPage, lastHomePage, state.homePages)
        val folders = state.folders.associate { it.id to it.appIds }
        return candidates.firstNotNullOfOrNull { MorphPlanner.tileFor(it.id, state.dock, state.homeSlots, folders, page, HOME_CELLS) }
    }

    private fun send(now: Landing, onScreen: MorphPlanner.Box) {
        val whenLanded = Message.obtain().apply { what = now.id; replyTo = landed }
        val data = Bundle().apply {
            putParcelable(EXTRA_ICON_POSITION, RectF(onScreen.left.toFloat(), onScreen.top.toFloat(), onScreen.right.toFloat(), onScreen.bottom.toFloat()))
            putParcelable(EXTRA_ON_FINISH_CALLBACK, whenLanded)
        }
        val reply = Message.obtain().apply { copyFrom(now.callback); this.data = data }
        now.callback.replyTo.send(reply)
    }

    private fun onLanded(id: Int) {
        val now = landing?.takeIf { it.id == id } ?: return
        now.done = true
        Log.i(TAG, "landed in ${now.tile}, ${now.age()} ms after the swipe")
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Bundle.parcel(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, T::class.java) else getParcelable(key) as? T

    // ---------------------------------------------------------- open: the start rect, and Home zooming toward it

    /**
     * ActivityOptions for opening [app] from the icon at [icon], in [source]'s window coordinates. [app] may be null for
     * a launch that is not an app of ours (a search); the card then knows the icon only by where it was laid out.
     */
    fun launchOptions(activity: ComponentActivity, source: View, icon: Rect, app: AppEntry? = null): Bundle {
        if (!enabled(activity)) return ActivityOptions.makeScaleUpAnimation(source, icon.left, icon.top, icon.width(), icon.height()).toBundle()
        // The card that grows out of the icon (OpenMorph.kt), when it is switched on and the stage is free. Home still
        // zooms toward the icon underneath it.
        runCatching { OpenMorph.begin(activity, source, icon, app) }.onFailure { Log.w(TAG, "open morph failed; the system's scale-up plays", it) }.getOrNull()?.let { options ->
            runCatching { zoomToward(activity, icon, source) }
            return options
        }
        val start = MorphPlanner.evenScaleStart(MorphPlanner.Box(icon.left, icon.top, icon.right, icon.bottom), source.width, source.height)
        runCatching { zoomToward(activity, icon, source) }.onFailure { Log.w(TAG, "Home zoom failed", it) }
        return ActivityOptions.makeScaleUpAnimation(source, start.left, start.top, start.width, start.height).toBundle()
    }

    private var zoom by mutableFloatStateOf(0f)
    private var pivot = Offset(.5f, .5f)
    private var zoomAnimator: ValueAnimator? = null
    private var zoomTarget = 0f
    private var watching: WeakReference<LifecycleOwner>? = null
    private val easeOut = PathInterpolator(.16f, 1f, .3f, 1f)
    private val settle = PathInterpolator(.2f, .9f, .25f, 1f)

    /**
     * For Home's root layer. Draw-only on purpose: a layout or layer transform would also move every icon's reported
     * bounds, and the landing above aims at those.
     */
    val homeZoom: Modifier = Modifier.drawWithContent {
        val z = zoom
        if (z <= 0f) drawContent()
        else {
            val s = 1f + ZOOM_SCALE * z
            scale(s, s, Offset(pivot.x * size.width, pivot.y * size.height)) { this@drawWithContent.drawContent() }
        }
    }

    private fun zoomToward(activity: ComponentActivity, icon: Rect, source: View) {
        if (!ValueAnimator.areAnimatorsEnabled() || source.width <= 0 || source.height <= 0) return
        // Watch first: a new observer is replayed onStart and onResume at once, and those settle the zoom.
        watch(activity)
        pivot = Offset((icon.exactCenterX() / source.width).coerceIn(0f, 1f), (icon.exactCenterY() / source.height).coerceIn(0f, 1f))
        animateZoom(1f, ZOOM_OUT_MS, easeOut)
        main.postDelayed({ if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) springBack() }, WATCHDOG_MS)
    }

    /** Home is back, or never left: let the zoom settle. */
    fun springBack() {
        if (zoom <= 0f && zoomTarget <= 0f) return
        if (zoomTarget == 0f && zoomAnimator?.isRunning == true) return
        animateZoom(0f, ZOOM_BACK_MS, settle)
    }

    private fun animateZoom(to: Float, ms: Long, curve: TimeInterpolator) {
        zoomAnimator?.cancel()
        zoomTarget = to
        zoomAnimator = ValueAnimator.ofFloat(zoom, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { zoom = it.animatedValue as Float }
            start()
        }
    }

    private fun watch(owner: LifecycleOwner) {
        if (watching?.get() === owner) return
        watching = WeakReference(owner)
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = springBack()
            override fun onResume(owner: LifecycleOwner) = springBack()
        })
    }
}
