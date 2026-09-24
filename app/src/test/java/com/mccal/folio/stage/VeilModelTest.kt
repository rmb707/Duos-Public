package com.mccal.folio.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeilModelTest {
    private val path = (0..20).map { it / 20f }

    @Test fun nothingIsDrawnAtRest() {
        for (u in path) assertEquals("u=$u", 0f, VeilModel.veilAlpha(0f, u), 1e-6f)
    }

    @Test fun fullStrengthIsDarkEverywhereButNeverBlack() {
        for (u in path) assertEquals("u=$u", VeilModel.MAX_DARK, VeilModel.veilAlpha(1f, u), 1e-4f)
    }

    /** The panels swap under this: if any part of the screen were still clear at full strength the swap would show. */
    @Test fun theFarEdgeIsTheLastToGoDark() {
        assertTrue(VeilModel.dark(.9f, 1f) < VeilModel.dark(.9f, 0f))
        assertEquals(1f, VeilModel.dark(1f, 1f), 1e-4f)
    }

    @Test fun darkGrowsWithStrengthAndFallsAlongThePath() {
        for (u in path) {
            var last = -1f
            for (step in 0..20) { val d = VeilModel.dark(step / 20f, u); assertTrue("m=${step / 20f} u=$u", d >= last - 1e-6f); last = d }
        }
        for (step in 1..19) {
            var last = 2f
            for (u in path) { val d = VeilModel.dark(step / 20f, u); assertTrue("m=${step / 20f} u=$u", d <= last + 1e-6f); last = d }
        }
    }

    /** The frost is what needs the pixels underneath; it must lead the dark, never trail it, or the dark would arrive bare. */
    @Test fun frostRunsAheadOfTheDark() {
        for (step in 1..19) for (u in path) assertTrue(VeilModel.frost(step / 20f, u) >= VeilModel.dark(step / 20f, u) - 1e-6f)
    }

    @Test fun frontMatchesTheHomeShader() {
        // DuoShader.sweepFront: f = pow(mc, 1.4) * (1 + w), w = 0.35
        assertEquals(0f, VeilModel.front(0f), 1e-6f)
        assertEquals(1.35f, VeilModel.front(1f), 1e-5f)
        assertEquals(Math.pow(.5, 1.4).toFloat() * 1.35f, VeilModel.front(.5f), 1e-5f)
        assertEquals(1.35f, VeilModel.front(1.5f), 1e-5f)      // intensity above 1 never moves the front past the end
    }
}
