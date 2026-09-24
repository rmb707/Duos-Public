package com.mccal.folio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/*
 * Fold8Duo (WP-49): Home again → page 1, the Android half of HomePress (HomeGestures.kt).
 *
 * MainActivity.onNewIntent reports each Home press here before anything is closed ([press]); LauncherScreen's Home
 * effect then asks where the pager goes ([target]) and moves it ([go]). Between presses this keeps the few things a
 * press can't see for itself: when Home's window last lost focus, whether the keyboard was up at that moment, and
 * whether Home has been covered since. Both steps are logged under FolioHome, so a press can be checked on the phone.
 */
internal object HomeAgain {
    const val TAG = "FolioHome"

    private var home: WeakReference<ComponentActivity>? = null
    private var focusLostAt = 0L
    private var imeWhenFocusLost = false
    private var stoppedSinceFocus = true
    /** Only logged, not decided on: whether One UI pauses Home during a swipe on Home is not known yet. */
    private var pausedSinceFocus = true
    private var pending: HomePressFacts? = null

    /** From MainActivity.onCreate: starts watching Home's window focus and its lifecycle. */
    fun attach(activity: ComponentActivity) {
        home = WeakReference(activity)
        focusLostAt = 0L; imeWhenFocusLost = false; stoppedSinceFocus = true; pausedSinceFocus = true; pending = null
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (home?.get() !== activity) return@addOnWindowFocusChangeListener
            if (focused) { stoppedSinceFocus = false; pausedSinceFocus = false; focusLostAt = 0L }
            else { focusLostAt = SystemClock.uptimeMillis(); imeWhenFocusLost = imeVisible(decor) }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) { if (home?.get() === activity) pausedSinceFocus = true }
            override fun onStop(owner: LifecycleOwner) { if (home?.get() === activity) stoppedSinceFocus = true }
        })
    }

    /**
     * From MainActivity.onNewIntent, for an intent that brings Home, before anything is closed. [closingApp] is the app
     * One UI's swipe-home contract named (IconMorph reads it); [overlayOpen] is what the activity itself has over Home.
     */
    fun press(activity: ComponentActivity, intent: Intent, closingApp: ComponentName?, overlayOpen: Boolean) {
        val hadFocus = activity.hasWindowFocus()
        val lostAgo = if (!hadFocus && focusLostAt > 0L) SystemClock.uptimeMillis() - focusLostAt else null
        val imeNow = imeVisible(activity.window.decorView)
        // Discover hands Home back with this extra: that is a return too.
        val fromApp = (closingApp != null && closingApp.packageName != activity.packageName) ||
            intent.getStringExtra("duo_destination") == "home"
        val facts = HomePressFacts(hadFocus, (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0, fromApp,
            stoppedSinceFocus, lostAgo, overlayOpen || HomePress.keyboardUp(imeNow, imeWhenFocusLost, lostAgo))
        pending = facts
        Log.i(TAG, "Home pressed: focus=$hadFocus broughtToFront=${facts.broughtToFront} closing=${closingApp?.flattenToShortString()} fromApp=$fromApp lostFocusMs=$lostAgo stoppedSinceFocus=$stoppedSinceFocus pausedSinceFocus=$pausedSinceFocus lifecycle=${activity.lifecycle.currentState} ime=$imeNow imeAtFocusLoss=$imeWhenFocusLost overlay=$overlayOpen -> alreadyOnHome=${HomePress.alreadyOnHome(facts)}")
    }

    /**
     * From LauncherScreen's Home effect, once per press: where the pager goes ([HomePress.target]), adding what is open
     * on Home itself ([sheet], [overlays], and [editing]: jiggle mode, a drag, or a widget being placed or resized).
     */
    fun target(focusPage: Int?, currentPage: Int, homePages: Int, libraryPage: Int, lastHomePage: Int,
        sheet: String, overlays: HomeOverlays, editing: Boolean): HomePress.Target {
        val facts = pending
        pending = null
        val already = facts != null && HomePress.alreadyOnHome(facts)
        val open = listOfNotNull(sheet.takeIf { it.isNotEmpty() }?.let { "sheet:$it" }, "editing".takeIf { editing },
            "overlay".takeIf { facts?.overlayOpen == true }) + overlays.openNames()
        val target = HomePress.target(already, open.isNotEmpty(), focusPage, currentPage, homePages, libraryPage, lastHomePage)
        Log.i(TAG, "Home: ${target.kind} -> page ${target.page} (was on page $currentPage of $homePages, library=$libraryPage, alreadyOnHome=$already, open=${open.joinToString("+").ifEmpty { "nothing" }})")
        return target
    }

    /** Moves Home's pager: back from an app exactly as before; otherwise a glide, or a jump with Reduce Motion on. */
    suspend fun go(pager: LauncherPager, target: HomePress.Target, context: Context) {
        if (target.kind != HomePress.Kind.RETURN && reduceMotionEnabled(context)) pager.scrollToPage(target.page)
        else pager.animateScrollToPage(target.page)
    }

    private fun imeVisible(view: View) =
        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
}

/** The overlays open on Home now, by name, for FolioHome's log. */
internal fun HomeOverlays.openNames(): List<String> = listOfNotNull(
    "menu".takeIf { menu != null }, "rename".takeIf { rename != null }, "panel".takeIf { panel != null },
    "stack".takeIf { stackFan != null || stackEditor != null }, "folder".takeIf { folder != null || newFolder != null },
    "cellMenu".takeIf { emptyCell != null })

/** A Home press closes every overlay on Home: LauncherScreen's Home effect closed some already, this is all of them. */
internal fun HomeOverlays.closeAll() {
    menu = null; rename = null; panel = null; stackFan = null; stackEditor = null; folder = null; newFolder = null; emptyCell = null
}
