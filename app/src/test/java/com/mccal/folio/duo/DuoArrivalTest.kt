package com.mccal.folio.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-51 r2: a fast open still shows the film's last stretch (the arrival floor under the moving half's tilt). */
class DuoArrivalTest {
    @Test fun theFloorStartsFullAndIsGoneByTheEnd() {
        assertEquals(DuoTiming.ARRIVAL_TILT_DEG, DuoTiming.arrivalTilt(0f), 1e-4f)
        assertEquals(0f, DuoTiming.arrivalTilt(DuoTiming.ARRIVAL_MS), 1e-4f)
        assertEquals(0f, DuoTiming.arrivalTilt(Float.MAX_VALUE), 0f)
        assertEquals(0f, DuoTiming.arrivalTilt(10_000f), 0f)
    }

    @Test fun theFloorSettlesSmoothlyNotInSteps() {
        var previous = DuoTiming.arrivalTilt(0f)
        var t = 0f
        while (t <= DuoTiming.ARRIVAL_MS) {
            val now = DuoTiming.arrivalTilt(t)
            assertTrue("t=$t", now <= previous + 1e-4f)
            assertTrue("t=$t", previous - now < DuoTiming.ARRIVAL_TILT_DEG * .12f)   // no jump bigger than ~12 % per 16 ms
            previous = now; t += 16f
        }
        val mid = DuoTiming.arrivalTilt(DuoTiming.ARRIVAL_MS / 2)
        assertTrue("$mid", mid > DuoTiming.ARRIVAL_TILT_DEG * .4f && mid < DuoTiming.ARRIVAL_TILT_DEG * .6f)
    }

    @Test fun aSlowOpenIsUntouchedByTheFloor() {
        // The panel lit at 56° with the hinge moving slowly: the hinge's own tilt stays above the floor until the floor is gone.
        val litAngle = 56f
        var sinceLit = 0f
        while (sinceLit < DuoTiming.ARRIVAL_MS) {
            val angle = litAngle + (178f - litAngle) * (sinceLit / 900f)   // a 900 ms open
            assertTrue("t=$sinceLit", DuoTiming.innerTiltFromAngle(angle) >= DuoTiming.arrivalTilt(sinceLit))
            sinceLit += 16f
        }
    }

    @Test fun aFastOpenShowsTheFloor() {
        // The panel lit at 160° and the hinge was flat 60 ms later: the angle alone would show 18° then nothing.
        assertTrue(DuoTiming.innerTiltFromAngle(160f) < DuoTiming.arrivalTilt(0f))
        assertTrue(DuoTiming.arrivalTilt(60f) > 30f)
        assertTrue(DuoTiming.arrivalTilt(200f) > 5f)
    }
}
