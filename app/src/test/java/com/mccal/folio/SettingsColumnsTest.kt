package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How many columns Settings shows, on the windows Folio actually meets. The rule is about the window, never the
 * device: the same 700 and 920 dp thresholds the Market and the Mockup Lab use.
 */
class SettingsColumnsTest {
    private fun columns(w: Float, h: Float, nested: Boolean = false, onFold: Boolean = false) =
        settingsColumns(w, h, nested = nested, onFold = onFold)

    @Test fun `a phone and a cover screen show one page at a time`() {
        assertEquals(1, columns(411f, 891f))          // Pixel 9
        assertEquals(1, columns(475f, 751f))          // Fold8 cover
        assertEquals(1, columns(751f, 475f))          // Fold8 cover, rotated: too short to be regular
        assertEquals(1, columns(411f, 891f, nested = true))
    }

    @Test fun `a window too narrow for two readable columns keeps the list over the page`() {
        assertEquals(1, columns(610f, 925f))          // 7.4" rollable
        assertEquals(1, columns(600f, 960f, nested = true))
    }

    @Test fun `the list sits beside the page in either orientation, which is what iPad Settings does`() {
        assertEquals(2, columns(932f, 704f))          // Fold8 inner
        assertEquals(2, columns(852f, 883f))          // Pixel 9 Pro Fold inner, held upright
        assertEquals(2, columns(800f, 1280f))         // tablet, portrait
        assertEquals(2, columns(1280f, 800f))         // tablet, landscape
    }

    @Test fun `a page opened from a list gets its own column when there is room`() {
        assertEquals(3, columns(932f, 704f, nested = true))    // Fold8 inner: Settings, Tweaks, the tweak
        assertEquals(3, columns(1280f, 800f, nested = true))
        // Under 920 dp a third column would leave all three too narrow, so the page still takes the list's place.
        assertEquals(2, columns(852f, 883f, nested = true))
        assertEquals(2, columns(838f, 945f, nested = true))    // 8" fold-out
    }

    @Test fun `half folded, the fold keeps the divider and there is no third column`() {
        assertEquals(2, columns(932f, 704f, nested = true, onFold = true))
        assertEquals(2, columns(1280f, 800f, nested = true, onFold = true))
    }
}
