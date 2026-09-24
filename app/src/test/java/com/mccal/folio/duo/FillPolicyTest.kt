package com.mccal.folio.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FillPolicyTest {
    private val yt = "com.google.android.youtube"
    private val fb = "com.facebook.katana"
    private val px = "ai.perplexity.app.android"     // set through Samsung's own settings, not by Folio
    private val cam = "com.sec.android.app.camera"   // a system app: never a candidate for the default
    private val none = emptySet<String>()

    @Test fun offByDefaultTouchesNothing() {
        assertTrue(FillPolicy.plan(false, setOf(yt, fb), none, none, current = none, ours = none).isEmpty)
        assertTrue(FillPolicy.plan(false, setOf(yt, fb), none, none, current = setOf(px), ours = none).isEmpty)
    }

    @Test fun theDefaultReachesOnlyCandidatesNotYetOn() {
        val plan = FillPolicy.plan(true, setOf(yt, fb), none, none, current = setOf(fb, px), ours = none)
        assertEquals(listOf(yt), plan.enable)
        assertEquals(emptyList<String>(), plan.reset)
    }

    @Test fun turningTheDefaultOffResetsOnlyWhatFolioSwitchedOn() {
        val plan = FillPolicy.plan(false, setOf(yt, fb), none, none, current = setOf(yt, fb, px), ours = setOf(yt, fb))
        assertEquals(listOf(fb, yt), plan.reset)
        assertEquals(emptyList<String>(), plan.enable)
    }

    @Test fun theOwnersChoiceWins() {
        // Off for one app beats the default, even one Folio switched on.
        val off = FillPolicy.plan(true, setOf(yt), none, setOf(yt), current = setOf(yt), ours = setOf(yt))
        assertEquals(listOf(yt), off.reset)
        // On for a system app reaches beyond the default's candidates.
        val on = FillPolicy.plan(false, none, setOf(cam), none, current = none, ours = none)
        assertEquals(listOf(cam), on.enable)
        assertTrue(FillPolicy.wants(cam, false, none, setOf(cam), none))
        assertFalse(FillPolicy.wants(yt, true, setOf(yt), none, setOf(yt)))
    }

    @Test fun anExplicitOffResetsEvenWhatSamsungSet() {
        val plan = FillPolicy.plan(false, none, none, setOf(px), current = setOf(px), ours = none)
        assertEquals(listOf(px), plan.reset)
    }

    @Test fun theAppInFrontIsNeverTouched() {
        val plan = FillPolicy.plan(true, setOf(yt, fb), none, none, current = none, ours = none, front = yt)
        assertEquals(listOf(fb), plan.enable)
    }

    @Test fun alreadyInLineMeansNothingToDo() {
        assertTrue(FillPolicy.plan(true, setOf(yt, fb), none, none, current = setOf(yt, fb), ours = setOf(yt, fb)).isEmpty)
    }
}
