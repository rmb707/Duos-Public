package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassSettingsTest {
    @Test fun `the middle of Wallpaper Tint is Folio's original tint and Clear turns it off`() {
        assertEquals(.28f, LauncherState().glassTintAmount, .001f)
        assertEquals(0f, LauncherState(tintedGlass = false, glassTint = .9f).glassTintAmount, 0f)
        assertEquals(.56f, LauncherState(glassTint = 1f).glassTintAmount, .001f)
    }

    @Test fun `Reduce Transparency makes glass nearly solid without lowering stronger values`() {
        val solid = LauncherState(widgetGlass = .1f, glassOutline = .05f).withSolidGlass()
        assertEquals(.9f, solid.widgetGlass, 0f); assertEquals(.45f, solid.glassOutline, 0f); assertEquals(.9f, solid.statusStyle.railGlass, 0f)
        assertEquals(.95f, LauncherState(widgetGlass = .95f).withSolidGlass().widgetGlass, 0f)
    }
}
