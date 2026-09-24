package com.mccal.folio.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real window growing out of its icon: where the shell's remote transition puts the window's surface (WP-53). */
class LeashFrameTest {
    private val cover = 1248 to 1972
    private val inner = 2448 to 1848
    private val icon = MorphPlanner.Box(790, 1494, 963, 1667)   // a 173 px tile, as Home reported it

    @Test fun atTheStartTheWindowsVisiblePartIsExactlyTheIcon() {
        val f = MorphPlanner.leashFrame(icon, cover.first, cover.second, 60f, 0f)
        // The window's width fills the icon's width…
        assertEquals(icon.width.toFloat(), f.scale * cover.first, .5f)
        // …its crop is as tall as the icon, centred in the window…
        assertEquals(icon.height.toFloat(), f.cropHeight * f.scale, 1f)
        assertEquals((cover.second - f.cropHeight) / 2, f.cropTop)
        // …and the visible part lands on the icon.
        assertEquals(icon.left.toFloat(), f.x, .5f)
        assertEquals(icon.top.toFloat(), f.y + f.cropTop * f.scale, 1f)
        // Corners: the icon's 22.5 % on screen, so larger in the window's own pixels before scaling.
        assertEquals(.225f * icon.width, f.cornerRadius * f.scale, .5f)
    }

    @Test fun atTheEndTheWindowIsWholeAndInPlace() {
        for ((w, h) in listOf(cover, inner)) {
            val f = MorphPlanner.leashFrame(icon, w, h, 60f, 1f)
            assertEquals(0f, f.x, 0f); assertEquals(0f, f.y, 0f)
            assertEquals(1f, f.scale, 0f)
            assertEquals(0, f.cropTop); assertEquals(h, f.cropHeight)
            assertEquals(60f, f.cornerRadius, 0f)
        }
    }

    @Test fun theVisiblePartAlwaysStaysInsideTheScreenAndGrowsMonotonically() {
        for ((w, h) in listOf(cover, inner)) {
            var lastWidth = 0f
            for (step in 0..20) {
                val p = step / 20f
                val f = MorphPlanner.leashFrame(icon, w, h, 60f, p)
                val visibleW = f.scale * w
                val visibleH = f.cropHeight * f.scale
                val top = f.y + f.cropTop * f.scale
                assertTrue("p=$p x=${f.x}", f.x >= -0.5f && f.x + visibleW <= w + 0.5f)
                assertTrue("p=$p top=$top", top >= -0.5f && top + visibleH <= h + 1f)
                assertTrue("p=$p", visibleW >= lastWidth - 1e-3f)
                assertTrue("p=$p crop", f.cropTop >= 0 && f.cropTop + f.cropHeight <= h)
                lastWidth = visibleW
            }
        }
    }

    @Test fun theSpringsCapSnapsTheWindowHome() {
        // 600 ms is the hard cap (SPEC morph.maxMs): by then the spring is well past 0.998 anyway.
        assertTrue(MorphPlanner.spring(.6f, .40f, .85f) > .998f)
    }
}
