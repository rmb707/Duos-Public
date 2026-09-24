package com.mccal.folio

import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch

/*
 * Fold8Duo (WP-49): swipe up on empty Home → the App Library, the Android half of SwipeUpGesture (HomeGestures.kt).
 *
 * Home's page gestures (PageGestures.kt, which covers the cover screen's pager and the unfolded workspace alike) hand an
 * upward drag that got past touch slop to [HomeSwipeUp.step]. If it started on empty Home, it is followed: every event
 * is taken, so nothing under the finger acts on it, until the finger has gone clearly up (the App Library opens), turns
 * sideways (the page swipe it was after all takes over), comes back down, or lifts. Anywhere else, nothing changes.
 */

/** Follows an upward swipe from empty Home for Home's page gestures, and opens the App Library when it is a clear one. */
internal class HomeSwipeUp {
    enum class Step {
        /** Not an upward swipe from empty Home: the page gestures carry on as they always did. */
        NONE,
        /** Being followed, not decided yet: this event is taken. */
        WAIT,
        /** A clear swipe up: the App Library is on its way. */
        OPEN,
        /** It was followed and came to nothing. */
        STOP,
    }

    /** Whether a swipe may start at a point in the page gestures' own coordinates. Set by Home on every composition. */
    internal var startsAt: (Offset) -> Boolean = { false }
    /** Opens the App Library the way the setting says. Set by Home on every composition. */
    internal var open: () -> Unit = {}
    private var following: PointerId? = null

    /**
     * One event of a gesture whose travel from [down] is past touch slop and mostly vertical. [velocityY] (px/s) is only
     * asked for when the finger lifts.
     */
    fun step(down: PointerInputChange, change: PointerInputChange, density: Float, velocityY: () -> Float): Step {
        val d = change.position - down.position
        if (following != down.id) {
            if (!change.pressed || d.y >= 0f || !startsAt(down.position)) return Step.NONE
            following = down.id
        }
        val verdict = SwipeUpGesture.verdict(d.x, d.y, density, change.pressed, if (change.pressed) 0f else velocityY())
        return when {
            verdict == SwipeUpGesture.Verdict.WAIT -> { change.consume(); Step.WAIT }
            // Asked again: jiggle mode or a page change since the finger went down means no.
            verdict == SwipeUpGesture.Verdict.OPEN && startsAt(down.position) -> {
                following = null; change.consume(); open(); Step.OPEN
            }
            else -> { following = null; Step.STOP }
        }
    }
}

/**
 * Home's [HomeSwipeUp]. [pages] are the Home pages on screen (one folded, the pair unfolded); [originInRoot] and
 * [originInWindow] place the page gestures' box; [onHomePage] is true on a real Home page with jiggle mode off.
 */
@Composable
internal fun rememberHomeSwipeUp(
    pager: LauncherPager, homePages: Int, drag: HomeDragState, pages: Set<Int>,
    originInRoot: () -> Offset, originInWindow: () -> Offset, onHomePage: () -> Boolean,
): HomeSwipeUp {
    val context = LocalContext.current
    val root = LocalView.current.rootView
    val density = LocalDensity.current.density
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val swipeUp = remember { HomeSwipeUp() }
    SideEffect {
        swipeUp.startsAt = startsAt@{ point ->
            if (HomeSwipeUpSetting.choice(context) == SwipeUpChoice.OFF || !onHomePage()) return@startsAt false
            val inRoot = point + originInRoot()
            // Not on an app, a folder, a widget or the dock: those have swipes of their own. An empty cell is fine.
            val item = drag.hit(inRoot, pages)
            if (item != null && !(item.target is DropTarget.Home && item.appId == null)) return@startsAt false
            // Across Home's own pages: not the Today View kept beside them unfolded, nor the side rail.
            val columns = drag.regions.values.filter { it.target is DropTarget.Home && it.page?.let(pages::contains) == true }
                .map { it.bounds.left..it.bounds.right }
            if (!SwipeUpGesture.withinColumns(inRoot.x, columns, SwipeUpGesture.COLUMN_SLACK_DP * density)) return@startsAt false
            val inWindow = point + originInWindow()
            val rootOnScreen = IntArray(2).also(root::getLocationOnScreen)
            !nativeWidgetConsumesVerticalGesture(root, inWindow + Offset(rootOnScreen[0].toFloat(), rootOnScreen[1].toFloat())) &&
                SwipeUpGesture.clearOfSystemGestures(inWindow.y, root.height.toFloat(), systemGestureBottom(root).toFloat(), density)
        }
        swipeUp.open = {
            val choice = HomeSwipeUpSetting.choice(context)
            Log.i(HomeAgain.TAG, "swipe up on Home -> App Library ($choice, reduceMotion=$reduceMotion)")
            scope.launch {
                if (reduceMotion) pager.scrollToPage(homePages) else pager.animateScrollToPage(homePages)
                // Only once the library is there: a swipe cut short must not leave the keyboard up over Home.
                if (choice == SwipeUpChoice.LIBRARY_KEYBOARD) librarySearchFocusRequests.intValue++
            }
        }
    }
    return swipeUp
}

/** The height of the system's gesture area along the bottom of [view]'s window, in px. */
private fun systemGestureBottom(view: View): Int {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return 0
    return maxOf(insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom,
        insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
}

/** Settings › Gestures & Actions › Swipe Up on Home, kept in the "folio" preferences, out of Folio's saved state. */
internal object HomeSwipeUpSetting {
    private const val PREFS = "folio"
    private const val KEY = "fold8duo.swipeUpHome"

    fun choice(context: Context): SwipeUpChoice = SwipeUpChoice.of(context.getSharedPreferences(PREFS, 0).getString(KEY, null))
    fun set(context: Context, choice: SwipeUpChoice) { context.getSharedPreferences(PREFS, 0).edit().putString(KEY, choice.name).apply() }
}

/** The row under Swipe Down on Home. */
@Composable
internal fun SwipeUpHomeRow() {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(HomeSwipeUpSetting.choice(context)) }
    IosMenuRow(stringResource(R.string.fold8_swipe_up_on_home),
        listOf(SwipeUpChoice.LIBRARY to stringResource(R.string.app_library),
            SwipeUpChoice.LIBRARY_KEYBOARD to stringResource(R.string.fold8_app_library_with_keyboard),
            SwipeUpChoice.OFF to stringResource(R.string.nothing)),
        choice, { choice = it; HomeSwipeUpSetting.set(context, it) }, tag = "swipe-up-home")
}

/**
 * The newest App Library search-focus request already answered. AppLibrary.kt answers each request once: without this, a
 * library page composed again later (paging past it, unfolding) would take the old request and raise the keyboard.
 */
internal object LibrarySearchFocus {
    var answered = 0
}
