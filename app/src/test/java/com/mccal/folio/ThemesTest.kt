package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    @Test fun `themes round-trip through their file and reject other files`() {
        FolioTheme.PRESETS.forEach { theme -> assertEquals(theme, FolioTheme.fromJson(theme.toJson().toString())) }
        assertNull(FolioTheme.fromJson("""{"apps":[]}"""))
        assertNull(FolioTheme.fromJson("not json"))
        val odd = FolioTheme.fromJson("""{"folioTheme":1,"name":"","iconStyle":"NEON","homeInk":"PURPLE","iconTint":255}""")!!
        assertEquals("Imported Theme", odd.name)
        assertEquals(IconStyle.DEFAULT, odd.iconStyle)
        assertEquals("AUTO", odd.homeInk)
        assertEquals(0xFF0000FFL, odd.iconTint)
    }

    @Test fun `applying a theme changes only the look and drops missing icon packs`() {
        val state = LauncherState(homeSlots = listOf("app"), iconStacks = mapOf("app" to listOf("b")))
        val themed = state.withTheme(FolioTheme("x", iconStyle = IconStyle.DARK, iconPack = "missing.pack"), installedPacks = emptySet())
        assertEquals(IconStyle.DARK, themed.iconStyle)
        assertNull(themed.iconPack)
        assertEquals(state.homeSlots, themed.homeSlots)
        assertEquals(state.iconStacks, themed.iconStacks)
        assertTrue(themed.looksLike(FolioTheme("any", iconStyle = IconStyle.DARK)))
    }
}
