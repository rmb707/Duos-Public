package com.mccal.folio

import com.mccal.folio.duo.ClosingTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fold8Duo: the front screen's view of a fast close, word by word, the way FoldTimeline.onAngle sees it: the engine's
 * words (each judged by [ClosingTail]), the public sensor's 0 landing between them, and the drop rule. After a late word
 * the tracker must still read shut at the sensor's 0, so neither of the front screen's opening checks (leaving the
 * closed band, or a word rising from the last angle) can fire.
 */
class LateCloseWordTest {
    private class Cover {
        val tracker = HingeTracker(HingeCapability.CONTINUOUS)
        val tail = ClosingTail()
        var dropped = 0
        private var t = 0L

        fun engine(deg: Float) {
            t += 50
            val isTail = tail.isTail(deg, t)
            if (ClosingTail.dropOnCover(isTail, expanded = false, closed = tracker.closed)) { dropped++; return }
            tracker.feed(deg, t * 1_000_000L)
        }
        fun sensor(deg: Float) { t += 50; tracker.feed(deg, t * 1_000_000L) }
    }

    @Test fun aLateSevenDegreeWordLeavesThePhoneShut() {
        val c = Cover()
        listOf(120f, 80f, 45f, 20f).forEach(c::engine)
        c.sensor(0f)
        c.engine(7f)   // 18:13:49
        assertTrue(c.tracker.closed)
        assertEquals(0f, c.tracker.raw)
        assertEquals(1, c.dropped)
    }

    @Test fun aLateWordAboveTheClosedBandLeavesThePhoneShutToo() {
        val c = Cover()
        listOf(60f, 40f, 27f).forEach(c::engine)
        c.sensor(0f)
        c.engine(15f)  // 18:16:07: above 12°, it would have left the closed band
        assertTrue(c.tracker.closed)
        assertEquals(0f, c.tracker.raw)
    }

    @Test fun reopeningStraightAwayStillLeavesTheClosedBand() {
        val c = Cover()
        listOf(60f, 30f, 9f).forEach(c::engine)
        c.sensor(0f)
        c.engine(14f)  // rising past the close's last word: the hinge really is opening
        assertFalse(c.tracker.closed)
        assertEquals(0, c.dropped)
    }

    @Test fun aReopenThatStartsLowIsOnlyAWordLate() {
        val c = Cover()
        listOf(60f, 30f, 9f).forEach(c::engine)
        c.sensor(0f)
        c.engine(7f)   // lower than the close's last word, inside the window: taken for the tail
        assertTrue(c.tracker.closed)
        c.engine(16f)  // the next word rises, so it is judged afresh and opens
        assertFalse(c.tracker.closed)
    }

    @Test fun onTheOpenScreenNothingIsDropped() {
        assertFalse(ClosingTail.dropOnCover(tail = true, expanded = true, closed = false))
        assertFalse(ClosingTail.dropOnCover(tail = true, expanded = false, closed = false))
        assertFalse(ClosingTail.dropOnCover(tail = false, expanded = false, closed = true))
    }
}
