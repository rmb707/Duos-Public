package com.mccal.folio.duo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosingTailTest {
    /** Feeds [words] 50 ms apart from [startMs]; returns the verdict on each. */
    private fun ClosingTail.feed(startMs: Long, vararg words: Float) = words.mapIndexed { i, w -> isTail(w, startMs + 50L * i) }

    @Test fun theLateLastWordOfAFastCloseIsTheTail() {
        // 18:13:49: the close's words fall, the panels swap, then the engine's 7° arrives after the sensor's 0.
        val t = ClosingTail()
        t.feed(0L, 120f, 80f, 45f, 20f)
        assertTrue(t.isTail(7f, 260L))
    }

    @Test fun aRisingWordIsAnOpeningEvenRightAfterAClose() {
        val t = ClosingTail()
        t.feed(0L, 60f, 30f, 7f)
        assertFalse(t.isTail(11f, 400L))   // opened again straight away: the hinge rose past the close's last word
    }

    @Test fun anOpeningAfterARestIsNeverTheTail() {
        val t = ClosingTail()
        t.feed(0L, 60f, 30f, 7f)
        assertFalse(t.isTail(6f, 30_000L))  // the first word of the next opening, lower than the last close's, much later
    }

    @Test fun theVeryFirstWordIsNeverTheTail() {
        assertFalse(ClosingTail().isTail(9f, 0L))
    }

    @Test fun anOpeningsOwnRisingWordsAreNeverTheTail() {
        assertTrue(ClosingTail().feed(0L, 8f, 17f, 29f, 44f, 70f).none { it })
    }

    @Test fun anEqualWordStillCountsAsTheTail() {
        // The HAL repeats itself at rest; a repeat is not the hinge moving up.
        val t = ClosingTail()
        t.feed(0L, 30f, 7f)
        assertTrue(t.isTail(7f, 120L))
    }

    @Test fun aClockThatStepsBackIsNotTrusted() {
        val t = ClosingTail()
        t.isTail(30f, 1_000L)
        assertFalse(t.isTail(7f, 900L))
    }
}
