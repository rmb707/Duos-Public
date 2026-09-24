package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fold8Duo: the real-angle model behind [HingeFeed]. The sample timings are from SM-F971U1 traces, where the HAL
 * reports every ~10° of hinge travel rather than on a clock: 40 ms apart on a fast swing, 360 ms on a slow one.
 */
class HingeFeedModelTest {
    @Test fun parsesFeederLines() {
        assertEquals(47f, HingeFeedModel.parse("A 47")!!, 0f)
        assertEquals(47f, HingeFeedModel.parse("A  47")!!, 0f)          // the HAL pads its numbers
        assertEquals(168.5f, HingeFeedModel.parse("  A 168.5  ")!!, 0f)
    }

    @Test fun rejectsEverythingElse() {
        assertNull(HingeFeedModel.parse(""))
        assertNull(HingeFeedModel.parse("A"))
        assertNull(HingeFeedModel.parse("B 47"))
        assertNull(HingeFeedModel.parse("A forty"))
        assertNull(HingeFeedModel.parse("A NaN"))
        assertNull(HingeFeedModel.parse("A 900"))
    }

    /** The front screen starts on the system's own opening signal, which carries the time left until the panels swap. */
    @Test fun parsesTheOpeningSignal() {
        assertEquals(405, HingeFeedModel.parseOpening("T 405"))
        assertEquals(405, HingeFeedModel.parseOpening("  T   405 "))
        assertEquals(0, HingeFeedModel.parseOpening("T"))              // no early light: timing unknown
        assertEquals(0, HingeFeedModel.parseOpening("T soon"))
        assertEquals(5_000, HingeFeedModel.parseOpening("T 999999"))   // never an absurd sweep
        assertNull(HingeFeedModel.parseOpening("A 47"))
        assertNull(HingeFeedModel.parseOpening(""))
    }

    @Test fun signalsAndAnglesDoNotGetMistakenForEachOther() {
        assertNull(HingeFeedModel.parse("T 405"))
        assertNull(HingeFeedModel.parse("C"))
        assertTrue(HingeFeedModel.isClosed("C"))
        assertTrue(HingeFeedModel.isClosed(" C "))
        assertTrue(!HingeFeedModel.isClosed("A 0"))
        assertTrue(!HingeFeedModel.isClosed("T 405"))
    }

    @Test fun nothingToDrawBeforeTheFirstSample() {
        assertTrue(HingeFeedModel().predicted(1_000).isNaN())
        assertTrue(HingeFeedModel().shown(1_000).isNaN())
    }

    @Test fun glidesOnAtTheHingesSpeedRightAfterAWord() {
        val model = HingeFeedModel()
        model.onSample(22f, 1_000)
        model.onSample(35f, 1_120)   // 13° in 120 ms
        assertEquals(35f, model.predicted(1_120), .01f)
        // Just after a word the eased glide is still close to the hinge's own speed.
        val after10ms = model.predicted(1_130) - 35f
        assertEquals(13f / 120f * 10f, after10ms, .15f)
    }

    /** The regression this model exists for: a slow open's words are ~360 ms apart, and the picture must not stop. */
    @Test fun neverFreezesBetweenTheWordsOfASlowOpen() {
        val model = HingeFeedModel()
        model.onSample(18f, 1_000)
        model.onSample(29f, 1_360)
        var last = model.predicted(1_360)
        for (t in 1_370L..1_720L step 10) {
            val now = model.predicted(t)
            assertTrue("stalled at t=$t ($last -> $now)", now > last)
            last = now
        }
    }

    @Test fun neverPassesWhereTheNextWordWouldBeDue() {
        val model = HingeFeedModel()
        model.onSample(80f, 1_000)
        model.onSample(92f, 1_120)
        assertTrue(model.predicted(60_000) <= 92f + HingeFeedModel.STEP_CAP_DEG)
        assertTrue(model.predicted(60_000) > 92f + HingeFeedModel.STEP_CAP_DEG - .1f)   // and it does get there
    }

    @Test fun aStoppedHandIsNeverMoreThanAStepWrong() {
        val model = HingeFeedModel()
        model.onSample(84f, 1_000)
        model.onSample(96f, 1_160)   // …and then the hand stops at ~97°
        for (t in listOf(1_200L, 2_000L, 10_000L)) assertTrue(model.predicted(t) - 96f <= HingeFeedModel.STEP_CAP_DEG)
    }

    @Test fun theFirstWordOfAnOpeningAlreadyMoves() {
        val model = HingeFeedModel()
        model.onSample(7f, 5_000)    // first word after a rest: no second word yet to measure a speed from
        assertTrue(model.velocity > 0f)
        assertTrue(model.predicted(5_100) > 7f)
    }

    @Test fun theFirstWordOfAClosingMovesTheOtherWay() {
        val model = HingeFeedModel()
        model.onSample(160f, 5_000)
        assertTrue(model.velocity < 0f)
        assertTrue(model.predicted(5_100) < 160f)
    }

    @Test fun aFirstWordMidWayAssumesNothing() {
        val model = HingeFeedModel()
        model.onSample(90f, 5_000)
        assertEquals(0f, model.velocity, 0f)
        assertEquals(90f, model.predicted(6_000), 0f)
    }

    @Test fun aRestBetweenMovesDoesNotCarrySpeedOver() {
        val model = HingeFeedModel()
        model.onSample(100f, 1_000)
        model.onSample(112f, 1_100)  // fast
        model.onSample(90f, 9_000)   // seconds later, mid-way: a new move, speed unknown
        assertEquals(0f, model.velocity, 0f)
    }

    @Test fun staysInsideTheHingesRange() {
        val model = HingeFeedModel()
        model.onSample(164f, 1_000)
        model.onSample(176f, 1_080)
        assertTrue(model.predicted(5_000) <= 180f)
        model.reset()
        model.onSample(16f, 2_000)
        model.onSample(4f, 2_080)
        assertTrue(model.predicted(9_000) >= 0f)
    }

    @Test fun oneLateLineDoesNotThrowThePictureForward() {
        val model = HingeFeedModel()
        model.onSample(20f, 1_000)
        model.onSample(32f, 1_120)   // 0.10°/ms
        model.onSample(62f, 1_240)   // a burst: 0.25°/ms
        assertTrue(model.velocity < .25f)
        assertTrue(model.velocity > .10f)
    }

    /** A new word corrects the prediction by a few degrees; what is drawn leans into it instead of ticking. */
    @Test fun whatIsDrawnLeansIntoACorrectionInsteadOfJumping() {
        val model = HingeFeedModel()
        model.onSample(40f, 1_000)
        model.onSample(51f, 1_360)
        var t = 1_360L
        while (t < 1_700L) { model.shown(t); t += 8 }        // a 120 Hz screen following the glide
        val before = model.shown(1_700)
        model.onSample(64f, 1_701)                           // the next word lands a few degrees ahead
        val after = model.shown(1_709)                       // one frame later
        assertTrue(after > before)
        assertTrue("jumped ${after - before}° in one frame", after - before < 2f)
    }

    @Test fun drawingIsRepeatableWithinAFrame() {
        val model = HingeFeedModel()
        model.onSample(40f, 1_000)
        model.onSample(51f, 1_200)
        val first = model.shown(1_300)
        assertEquals(first, model.shown(1_300), 0f)
    }
}
