package com.mccal.folio.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The landing that is sent once, and the card an app opens out of. */
class OpenCardTest {
    private fun tile(top: Int) = MorphPlanner.Box(790, top, 963, top + 173)

    // ---- movement filtering

    @Test fun theWobbleSeenOnThePhoneIsNotAMove() {
        // 1497 -> 1494 -> 1497 -> 1494 inside 85 ms at density 2.625: each one used to restart the system's spring.
        assertFalse(MorphPlanner.realMove(tile(1497), tile(1494), 2.625f))
        assertFalse(MorphPlanner.realMove(tile(1494), tile(1497), 2.625f))
    }

    @Test fun aPageSlidingBackIsFollowed() {
        assertTrue(MorphPlanner.realMove(tile(1494), tile(1494).offset(60, 0), 2.625f))
        assertTrue(MorphPlanner.realMove(tile(1494), tile(1494).offset(0, -40), 2.625f))
    }

    // ---- the opening card

    @Test fun theSpringStartsAtRestLandsOnOneAndBarelyOvershoots() {
        assertEquals(0f, MorphPlanner.spring(0f, .40f, .85f), 1e-6f)
        assertEquals(1f, MorphPlanner.spring(1.5f, .40f, .85f), 1e-3f)
        var peak = 0f
        var last = 0f
        for (ms in 0..1000 step 4) { val p = MorphPlanner.spring(ms / 1000f, .40f, .85f); peak = maxOf(peak, p); last = p }
        assertTrue("overshoot $peak", peak < 1.02f)
        assertEquals(1f, last, 2e-3f)
        assertTrue("settled before the app is let through", MorphPlanner.spring(.40f, .40f, .85f) > .985f)
    }

    @Test fun theSpringOnlyEverMovesForwardOnTheWayUp() {
        var last = 0f
        for (ms in 0..250 step 4) { val p = MorphPlanner.spring(ms / 1000f, .40f, .85f); assertTrue("at $ms ms", p >= last); last = p }
    }

    @Test fun theCardRunsFromTheIconToTheWholeScreen() {
        val icon = MorphPlanner.Box(552, 1494, 725, 1667)
        val start = MorphPlanner.card(icon, 1248, 1972, 0f)
        assertEquals(552f, start.left, 0f); assertEquals(1667f, start.bottom, 0f)
        val end = MorphPlanner.card(icon, 1248, 1972, 1f)
        assertEquals(0f, end.left, 0f); assertEquals(0f, end.top, 0f); assertEquals(1248f, end.right, 0f); assertEquals(1972f, end.bottom, 0f)
        val past = MorphPlanner.card(icon, 1248, 1972, 1.015f)                       // the spring's overshoot never leaves the screen
        assertEquals(1248f, past.width, 0f)
    }

    @Test fun cornersOpenFromAnIconsToTheScreens() {
        assertEquals(.225f * 173f, MorphPlanner.cornerRadius(173f, 96f, 0f), 1e-4f)
        assertEquals(96f, MorphPlanner.cornerRadius(173f, 96f, 1f), 1e-4f)
    }

    @Test fun theIconHasGoneAQuarterOfTheWayUp() {
        assertEquals(1f, MorphPlanner.iconAlpha(0f), 0f)
        assertEquals(0f, MorphPlanner.iconAlpha(.25f), 1e-6f)
        assertEquals(0f, MorphPlanner.iconAlpha(.9f), 0f)
    }
}
