package com.mccal.folio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every theme shared in the repo's themes/ folder has to load in Folio exactly as written. */
class CommunityThemesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").exists() }
    private val files = File(root, "themes").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test fun `the themes folder has themes`() = assertTrue(files.isNotEmpty())

    @Test fun `shared themes load, are small, and don't need an icon pack`() {
        for (file in files) {
            val raw = file.readText()
            assertTrue("${file.name} is over 64 KB", file.length() <= ThemeImportActivity.MAX_BYTES)
            val json = JSONObject(raw)
            assertEquals("${file.name} folioTheme", 1, json.optInt("folioTheme"))
            val theme = FolioTheme.fromJson(raw)
            assertNotNull("${file.name} isn't a Folio theme", theme)
            assertTrue("${file.name} name", json.optString("name").trim().length in 1..40)
            assertTrue("${file.name} iconPack must be null", json.isNull("iconPack") || !json.has("iconPack"))
            // Nothing is silently replaced by a default: every value survives a load and save.
            assertSame(file.name, json, theme!!.toJson())
        }
    }

    @Test fun `the example matches the built-in Dark theme`() {
        val example = FolioTheme.fromJson(File(root, "themes/dark-example.json").readText())!!
        assertEquals(FolioTheme.PRESETS.first { it.name == "Dark" }, example.copy(name = "Dark"))
    }

    private fun assertSame(where: String, expected: JSONObject, actual: JSONObject) {
        for (key in expected.keys()) {
            val e = expected.get(key); val a = actual.opt(key)
            when {
                e is JSONObject -> assertSame("$where.$key", e, a as JSONObject)
                e is Number && a is Number -> assertEquals("$where.$key", e.toDouble(), a.toDouble(), 1e-6)
                else -> assertEquals("$where.$key", e, a)
            }
        }
    }
}
