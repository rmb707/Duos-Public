package com.mccal.folio

import com.mccal.folio.HomePress.Kind.FIRST_PAGE
import com.mccal.folio.HomePress.Kind.RETURN
import com.mccal.folio.HomePress.Kind.STAY
import com.mccal.folio.SwipeUpGesture.Verdict.OPEN
import com.mccal.folio.SwipeUpGesture.Verdict.STOP
import com.mccal.folio.SwipeUpGesture.Verdict.WAIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGesturesTest {
    /** The Fold 8 at its stock 420 dpi. */
    private val density = 2.625f
    private fun dp(value: Float) = value * density

    /** A press with Home showing and in focus, nothing else going on: Launcher3's "already on Home". */
    private fun onHome() = HomePressFacts(hadFocus = true, broughtToFront = false, fromApp = false, stoppedSinceFocus = false,
        focusLostMsAgo = null, overlayOpen = false)

    /** One UI's swipe home from an app: no focus, Home covered since, the contract naming the app, brought to front. */
    private fun backFromApp() = HomePressFacts(hadFocus = false, broughtToFront = true, fromApp = true, stoppedSinceFocus = true,
        focusLostMsAgo = 42_000L, overlayOpen = false)

    @Test fun `Launcher3's test - focus and not brought to front is Home again, brought to front is a return`() {
        assertTrue(HomePress.alreadyOnHome(onHome()))
        assertFalse(HomePress.alreadyOnHome(onHome().copy(broughtToFront = true)))
        assertFalse(HomePress.alreadyOnHome(backFromApp()))
    }

    @Test fun `an app on its way home is a return whatever else is true`() {
        assertFalse(HomePress.alreadyOnHome(onHome().copy(fromApp = true)))
        assertFalse(HomePress.alreadyOnHome(onHome().copy(hadFocus = false, focusLostMsAgo = 200L, fromApp = true)))
    }

    @Test fun `without focus it is Home again only if the gesture itself took focus a moment ago`() {
        // One UI's swipe on Home: focus went to the gesture 300 ms ago, Home was never covered, brought to front or not.
        val swipe = onHome().copy(hadFocus = false, focusLostMsAgo = 300L)
        assertTrue(HomePress.alreadyOnHome(swipe))
        assertTrue(HomePress.alreadyOnHome(swipe.copy(broughtToFront = true)))
        assertTrue(HomePress.alreadyOnHome(swipe.copy(focusLostMsAgo = HomePress.GESTURE_FOCUS_MS)))
        // Focus went somewhere else long ago (Recents, a dialog), Home was covered since, or never had focus at all.
        assertFalse(HomePress.alreadyOnHome(swipe.copy(focusLostMsAgo = HomePress.GESTURE_FOCUS_MS + 1)))
        assertFalse(HomePress.alreadyOnHome(swipe.copy(stoppedSinceFocus = true)))
        assertFalse(HomePress.alreadyOnHome(swipe.copy(focusLostMsAgo = null)))
        assertFalse(HomePress.alreadyOnHome(swipe.copy(focusLostMsAgo = -5L)))
    }

    @Test fun `the keyboard counts when it is up, or was up as the gesture took focus`() {
        assertTrue(HomePress.keyboardUp(imeNow = true, imeWhenFocusLost = false, focusLostMsAgo = null))
        assertTrue(HomePress.keyboardUp(imeNow = false, imeWhenFocusLost = true, focusLostMsAgo = 250L))
        assertFalse(HomePress.keyboardUp(imeNow = false, imeWhenFocusLost = true, focusLostMsAgo = 60_000L))
        assertFalse(HomePress.keyboardUp(imeNow = false, imeWhenFocusLost = true, focusLostMsAgo = null))
        assertFalse(HomePress.keyboardUp(imeNow = false, imeWhenFocusLost = false, focusLostMsAgo = 250L))
    }

    @Test fun `back from an app keeps Folio's rule - the Focus page, else the Home page showing, else the last one`() {
        // Five Home pages, the library is page 5, the last Home page shown was 3.
        fun back(current: Int, focus: Int? = null) = HomePress.target(already = false, open = false, focusPage = focus,
            currentPage = current, homePages = 5, libraryPage = 5, lastHomePage = 3)
        assertEquals(HomePress.Target(2, RETURN), back(current = 2))
        assertEquals(HomePress.Target(3, RETURN), back(current = 5))    // left from the App Library
        assertEquals(HomePress.Target(3, RETURN), back(current = -1))   // left from Today View
        assertEquals(HomePress.Target(4, RETURN), back(current = 2, focus = 4))
        // Something open doesn't change a return: it is closed and Home comes back where it was.
        assertEquals(HomePress.Target(2, RETURN), HomePress.target(false, true, null, 2, 5, 5, 3))
    }

    @Test fun `Home again with nothing open goes to page 1, or the Focus's own page, from anywhere`() {
        fun again(current: Int, focus: Int? = null) = HomePress.target(already = true, open = false, focusPage = focus,
            currentPage = current, homePages = 5, libraryPage = 5, lastHomePage = 3)
        assertEquals(HomePress.Target(0, FIRST_PAGE), again(current = 3))
        assertEquals(HomePress.Target(0, FIRST_PAGE), again(current = 0))
        assertEquals(HomePress.Target(0, FIRST_PAGE), again(current = 5))   // the App Library
        assertEquals(HomePress.Target(0, FIRST_PAGE), again(current = -1))  // Today View
        assertEquals(HomePress.Target(2, FIRST_PAGE), again(current = 4, focus = 2))
        assertEquals(HomePress.Target(4, FIRST_PAGE), again(current = 1, focus = 9))  // kept to the pages there are
        assertEquals(HomePress.Target(0, FIRST_PAGE), HomePress.target(true, false, null, 0, 0, 0, 0))
    }

    @Test fun `Home again with something open only closes it and stays, library and Today included`() {
        fun stay(current: Int, library: Int = 5, focus: Int? = null) = HomePress.target(already = true, open = true,
            focusPage = focus, currentPage = current, homePages = 5, libraryPage = library, lastHomePage = 3)
        assertEquals(HomePress.Target(2, STAY), stay(current = 2))
        assertEquals(HomePress.Target(2, STAY), stay(current = 2, focus = 4))  // a Focus page waits for the next press
        assertEquals(HomePress.Target(5, STAY), stay(current = 5))             // library search with the keyboard up
        assertEquals(HomePress.Target(-1, STAY), stay(current = -1))
        // Mid-drag a sixth page is added before the library: the library stays the library once it goes...
        assertEquals(HomePress.Target(5, STAY), stay(current = 6, library = 6))
        // ...and the added page itself goes back to the last Home page.
        assertEquals(HomePress.Target(3, STAY), stay(current = 5, library = 6))
    }

    @Test fun `the first press closes what is open, the next one goes to page 1`() {
        val first = HomePress.target(HomePress.alreadyOnHome(onHome()), open = true, focusPage = null, currentPage = 3,
            homePages = 5, libraryPage = 5, lastHomePage = 3)
        val second = HomePress.target(HomePress.alreadyOnHome(onHome()), open = false, focusPage = null, currentPage = first.page,
            homePages = 5, libraryPage = 5, lastHomePage = 3)
        assertEquals(HomePress.Target(3, STAY), first)
        assertEquals(HomePress.Target(0, FIRST_PAGE), second)
    }

    @Test fun `a swipe up opens at 64 dp while held, and only when it is mostly upward`() {
        fun held(dx: Float, dy: Float) = SwipeUpGesture.verdict(dp(dx), dp(dy), density, pressed = true, velocityY = 0f)
        assertEquals(WAIT, held(0f, -10f))     // just past touch slop
        assertEquals(WAIT, held(2f, -63f))
        assertEquals(OPEN, held(0f, -64f))
        assertEquals(OPEN, held(38f, -64f))    // 0.6 sideways per unit up is still a swipe up
        assertEquals(WAIT, held(40f, -64f))    // too slanted to count yet
        assertEquals(OPEN, held(40f, -80f))    // ...until it straightens out
        assertEquals(STOP, held(3f, 0f))       // back where it started
        assertEquals(STOP, held(0f, 20f))      // and below
    }

    @Test fun `on release a quick flick counts from 32 dp, a slow or short one does not`() {
        fun lift(dx: Float, dy: Float, vy: Float) = SwipeUpGesture.verdict(dp(dx), dp(dy), density, pressed = false, velocityY = dp(vy))
        assertEquals(OPEN, lift(0f, -40f, -1200f))
        assertEquals(OPEN, lift(0f, -32f, -800f))
        assertEquals(STOP, lift(0f, -40f, -300f))   // let go slowly
        assertEquals(STOP, lift(0f, -20f, -3000f))  // a wobble, however quick
        assertEquals(OPEN, lift(0f, -70f, 0f))      // the whole way, then paused
        assertEquals(STOP, lift(60f, -70f, -2000f)) // more sideways than up
        assertEquals(STOP, lift(0f, 10f, 1000f))
    }

    @Test fun `a swipe starts well above the system's own bottom gesture area`() {
        val window = 1972f
        val inset = dp(32f)  // the system's gesture strip along the bottom
        val limit = window - dp(32f + 24f)
        assertTrue(SwipeUpGesture.clearOfSystemGestures(limit - 1f, window, inset, density))
        assertFalse(SwipeUpGesture.clearOfSystemGestures(limit + 1f, window, inset, density))
        // With no inset reported at all, the bottom 48 dp are still left alone.
        assertTrue(SwipeUpGesture.clearOfSystemGestures(window - dp(49f), window, 0f, density))
        assertFalse(SwipeUpGesture.clearOfSystemGestures(window - dp(47f), window, 0f, density))
    }

    @Test fun `a swipe starts across Home's own pages, give or take their margin`() {
        val pair = listOf(400f..1100f, 1250f..1950f)  // the two pages unfolded, the Today View left of 400
        val slack = dp(SwipeUpGesture.COLUMN_SLACK_DP)
        assertTrue(SwipeUpGesture.withinColumns(700f, pair, slack))
        assertTrue(SwipeUpGesture.withinColumns(1175f, pair, slack))   // the gap between the two pages
        assertTrue(SwipeUpGesture.withinColumns(400f - slack, pair, slack))
        assertFalse(SwipeUpGesture.withinColumns(300f, pair, slack))   // over the Today View
        assertFalse(SwipeUpGesture.withinColumns(2100f, pair, slack))  // over the side rail
        assertFalse(SwipeUpGesture.withinColumns(700f, emptyList(), slack))
    }

    @Test fun `the setting reads back what was saved, and the App Library for anything else`() {
        assertEquals(SwipeUpChoice.LIBRARY, SwipeUpChoice.of(null))
        assertEquals(SwipeUpChoice.LIBRARY, SwipeUpChoice.of("SOMETHING_NEWER"))
        SwipeUpChoice.entries.forEach { assertEquals(it, SwipeUpChoice.of(it.name)) }
        assertEquals(SwipeUpChoice.LIBRARY, SwipeUpChoice.DEFAULT)
    }
}
