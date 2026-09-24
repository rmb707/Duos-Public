package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusModesTest {
    @Test fun `saved focuses keep their settings and missing built-ins come back`() {
        val saved = listOf(DEFAULT_FOCUS_MODES[1].copy(homePage = 2, grayscale = true), FocusMode("gone", "Old", 0))
        val merged = FocusModes.withDefaults(saved)
        assertEquals(DEFAULT_FOCUS_MODES.size, merged.size)
        assertEquals(2, merged.first { it.id == "sleep" }.homePage)
        assertNull(merged.firstOrNull { it.id == "gone" })
    }
    @Test fun `home page stays inside the pages that exist`() {
        val work = DEFAULT_FOCUS_MODES.first { it.id == "work" }
        assertNull(FocusModes.homePage(work, 3))
        assertEquals(2, FocusModes.homePage(work.copy(homePage = 5), 3))
        assertEquals(1, FocusModes.homePage(work.copy(homePage = 1), 3))
        assertNull(FocusModes.homePage(null, 3))
        val updated = FocusModes.update(DEFAULT_FOCUS_MODES, work.copy(silence = false))
        assertEquals(false, updated.first { it.id == "work" }.silence)
    }
}
