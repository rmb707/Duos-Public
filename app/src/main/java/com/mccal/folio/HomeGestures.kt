package com.mccal.folio

import kotlin.math.abs

/*
 * Fold8Duo (WP-49): the rules behind two Home gestures, with no Android in them so they run as plain JVM tests
 * (HomeGesturesTest). HomeAgain.kt and HomeSwipeUp.kt are the Android halves.
 *
 * Home again: pressing Home while Home is already showing, with nothing open, glides back to the first Home page (or the
 * active Focus's own page), as on iPhone. A press that finds something open only closes it and Home stays where it is;
 * the next press goes to page 1. Coming back from an app keeps the page you left, exactly as before.
 *
 * Swipe up: an upward swipe on empty Home opens the App Library once it is clearly a swipe up, not a wobble.
 */

/** What Home knew when a Home press arrived, taken before anything was closed. */
internal data class HomePressFacts(
    /** Home's window had input focus: one half of Launcher3's test for "already on Home". */
    val hadFocus: Boolean,
    /** The intent carried FLAG_ACTIVITY_BROUGHT_TO_FRONT, Home's task came up from behind another: the other half. */
    val broughtToFront: Boolean,
    /** An app's window is on its way home (One UI's swipe-home contract named it, or Discover handed back): a return. */
    val fromApp: Boolean,
    /** Home was stopped, fully covered, at some point since it last had focus. */
    val stoppedSinceFocus: Boolean,
    /** How long ago Home's window lost focus; null while it has focus, or when it never had it. */
    val focusLostMsAgo: Long?,
    /** Something over Home that this press closes: Spotlight, a top panel, the Lock Cover, setup, the keyboard. */
    val overlayOpen: Boolean,
)

internal object HomePress {
    /**
     * One UI's swipe home can take focus off Home for as long as the gesture runs and then start Home as if it came from
     * behind another task. Focus lost longer ago than this was taken by something else, so that press is not Home again.
     */
    const val GESTURE_FOCUS_MS = 1_500L

    enum class Kind {
        /** Back from an app (or anything else in front): the page you left, or the Focus's page. Folio's rule, unchanged. */
        RETURN,
        /** Home was showing with something open: the press only closes it, and Home stays on its page. */
        STAY,
        /** Home again with nothing open: the first Home page, or the active Focus's own page. */
        FIRST_PAGE,
    }

    data class Target(val page: Int, val kind: Kind)

    /**
     * Was Home already on screen, in front, when Home was pressed? Launcher3 asks two things in its onNewIntent: did the
     * window have focus, and did the intent not bring Home's task to the front. This starts there. On One UI a swipe home
     * can take focus for the length of the gesture and deliver the intent as if Home came from behind, so a press also
     * counts when Home lost focus only a moment ago and has not been covered since. An app on its way home never counts.
     */
    fun alreadyOnHome(facts: HomePressFacts): Boolean = when {
        facts.fromApp -> false
        facts.hadFocus -> !facts.broughtToFront
        else -> !facts.stoppedSinceFocus && facts.focusLostMsAgo != null && facts.focusLostMsAgo in 0..GESTURE_FOCUS_MS
    }

    /** The keyboard counts as open when it is up now, or was up when the gesture took Home's focus a moment ago. */
    fun keyboardUp(imeNow: Boolean, imeWhenFocusLost: Boolean, focusLostMsAgo: Long?): Boolean =
        imeNow || (imeWhenFocusLost && focusLostMsAgo != null && focusLostMsAgo in 0..GESTURE_FOCUS_MS)

    /**
     * Where Home's pager goes for a press. Pages are Home's own numbers: -1 left of Home (Today View or Discover), 0 until
     * [homePages] for Home, and [libraryPage] for the App Library (one further on while a drag has added a page).
     * [focusPage] is the active Focus's own Home page, if it has one.
     */
    fun target(already: Boolean, open: Boolean, focusPage: Int?, currentPage: Int, homePages: Int, libraryPage: Int,
        lastHomePage: Int): Target {
        val lastPage = (homePages - 1).coerceAtLeast(0)
        return when {
            !already -> Target(focusPage ?: currentPage.takeIf { it in 0 until homePages } ?: lastHomePage.coerceIn(0, lastPage),
                Kind.RETURN)
            open -> Target(when {
                currentPage in -1 until homePages -> currentPage
                currentPage == libraryPage -> homePages      // the library, where it will be once a dragged-in page is gone
                else -> lastHomePage.coerceIn(0, lastPage)   // a page a drag added, which the press takes away
            }, Kind.STAY)
            else -> Target((focusPage ?: 0).coerceIn(0, lastPage), Kind.FIRST_PAGE)
        }
    }
}

/** What an upward swipe on empty Home does: Settings › Gestures & Actions › Swipe Up on Home. */
internal enum class SwipeUpChoice {
    /** The App Library page, the same page as a swipe past the last Home page. */
    LIBRARY,
    /** The App Library with its search field ready and the keyboard up. */
    LIBRARY_KEYBOARD,
    OFF;

    companion object {
        val DEFAULT = LIBRARY
        /** A saved choice; nothing saved, or anything this version doesn't know, is the default. */
        fun of(saved: String?): SwipeUpChoice = entries.firstOrNull { it.name == saved } ?: DEFAULT
    }
}

/**
 * When an upward swipe on empty Home counts. Distances are px from where the finger went down, [density] is px per dp,
 * velocities are px per second with up negative (Compose's).
 */
internal object SwipeUpGesture {
    /** Upward travel that makes it a swipe while the finger is still down. An icon's own swipe up needs 28 dp. */
    const val OPEN_DP = 64f
    /** A shorter swipe counts when the finger leaves the glass moving up at least [FLICK_DP_PER_S]. */
    const val FLICK_DP = 32f
    const val FLICK_DP_PER_S = 800f
    /** Sideways travel allowed per unit of upward travel: within about 31° of straight up, as for an icon's swipe up. */
    const val SLANT = .6f
    /** A swipe must start this far above the system's own bottom gesture area... */
    const val CLEAR_OF_SYSTEM_DP = 24f
    /** ...and never closer than this to the bottom of the window, whatever the insets say. */
    const val BOTTOM_ZONE_MIN_DP = 48f
    /** How far outside the Home pages' columns a swipe may still start: a page's own side margin. */
    const val COLUMN_SLACK_DP = 16f

    enum class Verdict {
        /** Not yet a swipe up; keep following. */
        WAIT,
        OPEN,
        /** Came to nothing: it went back down, or the finger left too soon, too slowly or too far sideways. */
        STOP,
    }

    fun verdict(dx: Float, dy: Float, density: Float, pressed: Boolean, velocityY: Float): Verdict {
        val up = -dy
        val straight = up > 0f && abs(dx) <= SLANT * up
        return when {
            pressed && up <= 0f -> Verdict.STOP
            pressed -> if (straight && up >= OPEN_DP * density) Verdict.OPEN else Verdict.WAIT
            straight && (up >= OPEN_DP * density || (up >= FLICK_DP * density && -velocityY >= FLICK_DP_PER_S * density)) ->
                Verdict.OPEN
            else -> Verdict.STOP
        }
    }

    /** Whether a swipe starting [y] px down a window [windowHeight] px tall is well above the system's bottom gestures. */
    fun clearOfSystemGestures(y: Float, windowHeight: Float, gestureInsetBottom: Float, density: Float): Boolean =
        y < windowHeight - maxOf(gestureInsetBottom + CLEAR_OF_SYSTEM_DP * density, BOTTOM_ZONE_MIN_DP * density)

    /** Whether [x] lies across the Home pages' columns (each page's cells, left to right), give or take [slack]. */
    fun withinColumns(x: Float, columns: List<ClosedFloatingPointRange<Float>>, slack: Float): Boolean =
        columns.isNotEmpty() && x >= columns.minOf { it.start } - slack && x <= columns.maxOf { it.endInclusive } + slack
}
