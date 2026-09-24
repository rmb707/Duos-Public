package com.mccal.folio.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class DuoPaneModelTest {
    /** The Fold 8's inner half: 1224 px at 420 dpi. */
    private val pane = 74f
    private val points = (0..20).map { it * pane / 20f }

    @Test fun flatIsTheScreenItself() {
        for (d in points) assertEquals("d=$d", d, DuoPaneModel.source(d, 0f, pane), 1e-4f)
        assertEquals(0f, DuoPaneModel.blurRadius(pane, 0f, 1f), 1e-6f)
        assertEquals(0f, DuoPaneModel.dark(0f, 1f), 1e-6f)
        assertEquals(0f, DuoPaneModel.paneAlpha(0f), 1e-6f)
    }

    /**
     * A tilted pane shows its page from the hinge outward: the further along the pane, the further along the page, and
     * once it is well tilted every pixel looks nearer the hinge than it is. (At a slight tilt the free edge, nearer the
     * eye, looks a hair past itself — the ray's parallax outweighs the foreshortening — which the page's edge clamps.)
     */
    @Test fun aTiltedPaneShowsThePageFromTheHingeOutward() {
        for (tilt in listOf(10f, 30f, 60f)) {
            var last = -1f
            for (d in points) {
                val s = DuoPaneModel.source(d, tilt, pane)
                if (tilt >= 30f) assertTrue("tilt=$tilt d=$d", s <= d + 1e-4f)
                assertTrue("monotonic tilt=$tilt d=$d", s >= last - 1e-4f)
                last = s
            }
        }
        val hair = DuoPaneModel.source(pane * .95f, 10f, pane) - pane * .95f
        assertTrue("$hair", hair in 0f..pane * .03f)
    }

    /** The eye is at a finite distance: the free edge, nearer the eye, looks further out than a flat projection would. */
    @Test fun theEyeSeesMoreOfThePageThanAnOrthographicProjection() {
        val s = DuoPaneModel.source(pane, 60f, pane)
        val orthographic = pane * cos(DuoPaneModel.radians(60f))
        assertTrue(s > orthographic)
        assertTrue(s < pane)
        assertEquals(46.25f, s, .3f)      // 74·0.5·320/(320−64), by hand
    }

    @Test fun theProjectionNeverLeavesThePageAndNeverStretchesPastTheCap() {
        for (tilt in listOf(0f, 20f, 70f, 90f, 150f)) for (d in points) {
            val s = DuoPaneModel.source(d, tilt, pane)
            assertTrue("tilt=$tilt d=$d", s in 0f..pane)
        }
        val atCap = DuoPaneModel.source(pane, DuoPaneModel.PROJECTION_MAX_TILT_DEG, pane)
        assertTrue("$atCap", atCap >= pane / DuoPaneModel.MAX_STRETCH)
        assertEquals(atCap, DuoPaneModel.source(pane, 89f, pane), 1e-4f)
    }

    /** The film: the blur grows to about a tenth of the half by ~60° and stays there; more at the free edge than the hinge. */
    @Test fun blurGrowsWithTheTiltToATenthOfThePane() {
        var last = -1f
        for (t in 0..180 step 5) { val r = DuoPaneModel.blurRadius(pane, t.toFloat(), 1f); assertTrue("t=$t", r >= last); last = r }
        assertEquals(pane * .1f, DuoPaneModel.blurRadius(pane, 60f, 1f), 1e-4f)
        assertEquals(pane * .1f, DuoPaneModel.blurRadius(pane, 150f, 1f), 1e-4f)
        assertEquals(pane * .05f, DuoPaneModel.blurRadius(pane, 30f, 1f), 1e-4f)
        assertTrue(DuoPaneModel.blurRadius(pane, 60f, 0f) < DuoPaneModel.blurRadius(pane, 60f, .5f))
        assertEquals(DuoPaneModel.BLUR_AT_HINGE, DuoPaneModel.blurRadius(pane, 60f, 0f) / DuoPaneModel.blurRadius(pane, 60f, 1f), 1e-4f)
        assertTrue(DuoPaneModel.blurRadius(pane, 60f, 1f, 1.5f) > DuoPaneModel.blurRadius(pane, 60f, 1f))
    }

    /** The film's guitar body: 93 % bright at ~5°, 87 % at ~15°, 71 % at ~35°, 63 % at ~45°, 53 % from ~60° on. */
    @Test fun darkMidPaneFollowsTheFilmsCurve() {
        fun bright(t: Float) = 1f - DuoPaneModel.darkMid(t)
        assertEquals(.93f, bright(5f), .04f)
        assertEquals(.87f, bright(15f), .04f)
        assertEquals(.71f, bright(35f), .04f)
        assertEquals(.63f, bright(45f), .04f)
        assertEquals(.53f, bright(60f), .04f)
        assertEquals(.53f, bright(85f), .04f)
        var last = -1f
        for (t in 0..180 step 5) { val d = DuoPaneModel.darkMid(t.toFloat()); assertTrue(d >= last); last = d }
    }

    @Test fun darkerTowardTheFreeEdgeAndNeverPastTheCap() {
        assertTrue(DuoPaneModel.dark(60f, 0f) < DuoPaneModel.dark(60f, .5f))
        assertTrue(DuoPaneModel.dark(60f, .5f) < DuoPaneModel.dark(60f, 1f))
        assertEquals(DuoPaneModel.darkMid(60f), DuoPaneModel.dark(60f, .5f), 1e-4f)      // mid-pane is the measured point
        for (t in 0..180 step 10) for (e in 0..10) assertTrue(DuoPaneModel.dark(t.toFloat(), e / 10f, 1.5f) <= DuoPaneModel.DARK_CAP + 1e-6f)
    }

    /** Turned well past the vertical the pane is a dim blur at about half brightness, never black (the film at ~150°). */
    @Test fun aPaneTurnedPastTheVerticalDimsButStaysVisible() {
        assertEquals(0f, DuoPaneModel.steepDark(90f), 1e-6f)
        assertTrue(DuoPaneModel.steepDark(150f) in .45f..0.6f)
        for (e in 0..10) {
            val d = DuoPaneModel.dark(150f, e / 10f)
            assertTrue("e=$e $d", d in .45f..DuoPaneModel.DARK_CAP + 1e-6f)
        }
        // and at 100° the hinge side is still lighter than the free edge: the gradient shows through
        assertTrue(DuoPaneModel.dark(100f, 0f) < DuoPaneModel.dark(100f, 1f))
    }

    @Test fun thePaneFadesInOverItsFirstDegrees() {
        assertEquals(1f, DuoPaneModel.paneAlpha(DuoPaneModel.PANE_FADE_IN_DEG), 1e-6f)
        assertTrue(DuoPaneModel.paneAlpha(1f) in .3f..0.7f)
    }
}

class DuoTimingTest {
    @Test fun strengthAndAngleAreEachOthersInverse() {
        assertEquals(0f, DuoTiming.innerTilt(0f), 1e-6f)
        assertEquals(163f, DuoTiming.innerTilt(1f), 1e-6f)
        assertEquals(175f, DuoTiming.angleFromStrength(0f), 1e-6f)
        assertEquals(12f, DuoTiming.angleFromStrength(1f), 1e-6f)
        assertEquals(93.5f, DuoTiming.angleFromStrength(.5f), 1e-4f)
    }

    @Test fun thePanelsSwapUnderDark() {
        assertEquals(0f, DuoTiming.coverDusk(.6f), 1e-6f)
        assertEquals(1f, DuoTiming.coverDusk(1f), 1e-6f)
        assertTrue(DuoTiming.coverDusk(.8f) in .4f..0.6f)
        assertEquals(1f, DuoTiming.dawn(0f), 1e-6f)
        assertEquals(0f, DuoTiming.dawn(DuoTiming.DAWN_MS), 1e-6f)
        val end = DuoTiming.closeDuskEndDeg(earlyCover = true, earlyCoverRequestDeg = 40f)
        assertEquals(36f, end, 1e-6f)
        assertEquals(0f, DuoTiming.closingDusk(60f, end), 1e-6f)
        assertEquals(0f, DuoTiming.closingDusk(46f, end), 1e-6f)      // the fixed half stays bright until just before the swap
        assertEquals(1f, DuoTiming.closingDusk(36f, end), 1e-6f)
        assertEquals(1f, DuoTiming.closingDusk(20f, end), 1e-6f)
        assertTrue(DuoTiming.closingDusk(41f, end) in .4f..0.6f)
        assertEquals(12f, DuoTiming.closeDuskEndDeg(earlyCover = false, earlyCoverRequestDeg = 40f), 1e-6f)
    }

    /** The film's fold effect starts the instant the hinge leaves flat: no flat band with the real angle. */
    @Test fun theTiltFollowsTheRealAngleFromTheFirstDegree() {
        assertEquals(0f, DuoTiming.innerTiltFromAngle(180f), 1e-6f)
        assertEquals(0f, DuoTiming.innerTiltFromAngle(178f), 1e-6f)
        assertEquals(8f, DuoTiming.innerTiltFromAngle(170f), 1e-6f)
        assertEquals(163f, DuoTiming.innerTiltFromAngle(10f), 1e-6f)
        assertEquals(163f, DuoTiming.innerTiltFromAngle(0f), 1e-6f)
    }

    @Test fun theCoverStillHoldsThenMeltsAsThePhoneOpens() {
        assertEquals(1f, DuoTiming.stillWhileOpening(30f, 0f), 1e-6f)
        assertEquals(1f, DuoTiming.stillWhileOpening(55f, 300f), 1e-6f)
        assertEquals(0f, DuoTiming.stillWhileOpening(120f, 300f), 1e-6f)
        assertEquals(0f, DuoTiming.stillWhileOpening(180f, 300f), 1e-6f)
        assertEquals(1f, DuoTiming.stillWhileOpening(40f, DuoTiming.STILL_HOLD_MS), 1e-6f)
        assertEquals(0f, DuoTiming.stillWhileOpening(40f, DuoTiming.STILL_HOLD_MS + DuoTiming.STILL_MELT_MS), 1e-6f)
    }
}

class PaneSpringTest {
    private fun run(spring: PaneSpring, target: Float, ms: Int, dt: Float): List<Float> {
        val out = ArrayList<Float>()
        var t = 0f
        while (t < ms) { out += spring.step(target, dt); t += dt }
        return out
    }

    @Test fun settlesOnTheTargetWithATinyOvershoot() {
        val spring = PaneSpring()
        val path = run(spring, 1f, 600, 16f)
        assertTrue(spring.settled(1f))
        val peak = path.max()
        assertTrue("overshoot ${peak - 1f}", peak - 1f in 0f..0.08f)
        assertEquals(PaneSpring.overshoot, peak - 1f, .01f)
    }

    @Test fun reachesMostOfTheWayWithinItsResponseTime() {
        val path = run(PaneSpring(), 1f, 160, 8f)
        assertTrue("${path.last()}", path.last() > .8f)
    }

    @Test fun isFrameRateIndependent() {
        val fine = run(PaneSpring(), 1f, 400, 4f)
        val coarse = run(PaneSpring(), 1f, 400, 32f)
        for (i in coarse.indices) {
            val at = (i + 1) * 32f
            val fineIndex = (at / 4f).toInt() - 1
            if (fineIndex >= fine.size) break
            assertEquals("t=$at", fine[fineIndex], coarse[i], .02f)
        }
    }

    @Test fun survivesBadClocks() {
        val spring = PaneSpring()
        assertFalse(spring.step(1f, 0f).isNaN())
        assertFalse(spring.step(1f, -5f).isNaN())
        assertFalse(spring.step(1f, 5_000f).isNaN())
        assertTrue(abs(spring.value) <= 1.1f)
        spring.snap(.5f)
        assertEquals(.5f, spring.value, 0f)
        assertEquals(0f, spring.velocity, 0f)
    }
}
