package com.mccal.folio.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovingHalfTest {
    /** Runs a fold from [from] to [to] degrees over [ms], with the IMU half turning [share] of it. */
    private fun fold(m: MovingHalf, from: Float, to: Float, ms: Int, share: Float) {
        val steps = ms / 10
        val perStep = (to - from) / steps
        for (i in 1..steps) {
            m.onGyro(share * perStep / .010f, .010f)               // deg/s over 10 ms
            m.onHinge(from + perStep * i, (i * 10).toLong())
        }
    }

    @Test fun unknownUntilMeasuredIsBothHalves() {
        val m = MovingHalf()
        assertEquals(.5f, m.alpha, 0f)
        assertFalse(m.measured)
        assertEquals(60f to 60f, m.tilts(120f))
    }

    @Test fun theImuHalfHeldStillGivesZero() {
        val m = MovingHalf()
        fold(m, 180f, 60f, 600, share = 0f)
        assertTrue(m.measured)
        assertEquals(0f, m.alpha, .02f)
        assertEquals(0f to 120f, m.tilts(120f))
    }

    @Test fun theImuHalfDoingAllTheMovingGivesOne() {
        val m = MovingHalf()
        fold(m, 180f, 60f, 600, share = 1f)
        assertEquals(1f, m.alpha, .02f)
    }

    @Test fun bothHalvesSwingingGivesAHalf() {
        val m = MovingHalf()
        fold(m, 180f, 60f, 600, share = .5f)
        assertEquals(.5f, m.alpha, .03f)
    }

    @Test fun aChangeOfGripIsFollowedSmoothlyNotFlipped() {
        val m = MovingHalf()
        fold(m, 180f, 90f, 500, share = 0f)
        assertEquals(0f, m.alpha, .02f)
        // One noisy 8° step claiming the IMU half moved: the low-pass keeps it from flipping the picture.
        m.reset()
        m.onHinge(90f, 1_000)
        m.onGyro(800f, .010f)   // 8° of gyro in 10 ms
        m.onHinge(82f, 1_010)
        assertTrue("${m.alpha}", m.alpha < .1f)
        // A sustained change is followed within a few hundred ms.
        fold(m, 82f, 20f, 600, share = 1f)
        assertTrue("${m.alpha}", m.alpha > .8f)
    }

    @Test fun tinyHingeMovesNeverMeasure() {
        val m = MovingHalf()
        m.onHinge(180f, 0)
        m.onGyro(100f, .010f)
        m.onHinge(177f, 10)           // 3°: under the threshold
        assertFalse(m.measured)
        assertEquals(.5f, m.alpha, 0f)
    }

    @Test fun badTimingIsIgnored() {
        val m = MovingHalf()
        m.onGyro(1_000f, 0f); m.onGyro(1_000f, -1f); m.onGyro(1_000f, 5f)
        m.onHinge(180f, 0); m.onHinge(160f, 100)
        assertEquals(0f, m.alpha, 1e-6f)   // no gyro was counted, so the IMU half was "still"
    }
}
