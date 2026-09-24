package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the ring and the line under it say.
 *
 * The rule being tested is restraint: no estimate from too little data, no fake precision, and nothing at all when
 * the source didn't say how big the download is.
 */
class MarketProgressTest {
    private fun downloading(bytes: Long, total: Long, afterMs: Long) = MarketProgress(
        MarketProgress.Phase.DOWNLOADING, bytes = bytes, total = total, startedAt = 0, now = afterMs,
    )

    @Test fun `the ring fills with the download`() {
        assertEquals(0.25f, downloading(250, 1_000, 500).fraction)
        assertEquals(1f, downloading(1_000, 1_000, 500).fraction)
        // Over-reporting can't push the ring past full.
        assertEquals(1f, downloading(1_200, 1_000, 500).fraction)
    }

    @Test fun `no length from the source means no ring, and no invented percentage`() {
        val unknown = downloading(500, -1, 3_000)
        assertNull(unknown.fraction)
        assertNull(unknown.wording)
    }

    @Test fun `the first second says how big it is, not how long it will take`() {
        // A rate measured over 400 ms of a mobile connection is a guess; "5.0 of 20.0 MB" is a fact.
        val early = downloading(5 * 1_048_576, 20 * 1_048_576, 400)
        assertEquals(MarketProgress.Wording.Megabytes("5.0", "20.0"), early.wording)
    }

    @Test fun `the estimate is rounded to something worth reading`() {
        // Half of 20 MB in two seconds: ten more MB at that rate is about two seconds.
        assertEquals(MarketProgress.Wording.NearlyDone, downloading(10_000_000, 20_000_000, 2_000).wording)
        // A tenth in two seconds: eighteen seconds left, said as twenty.
        assertEquals(MarketProgress.Wording.Seconds(20), downloading(2_000_000, 20_000_000, 2_000).wording)
        // A fiftieth in five seconds: four minutes, said as four minutes.
        assertEquals(MarketProgress.Wording.Minutes(4), downloading(400_000, 20_000_000, 5_000).wording)
    }

    @Test fun `applying says so, because there are no bytes left to count`() {
        val applying = MarketProgress(MarketProgress.Phase.APPLYING)
        assertNull(applying.fraction)
        assertEquals(MarketProgress.Wording.Applying, applying.wording)
    }
}
