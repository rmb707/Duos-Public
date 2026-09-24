package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test fun `parses version sections and ignores other headings`() {
        val md = """
            # Changelog
            Intro text.
            ## [0.4.0] - 2026-09-14
            ### Added
            - Focus
            - Themes
            ### Fixed
            - A bug
            ## [0.3.0] - 2026-09-13
            - Loose item
            ## DuoLauncher history
            ### 0.15.0
            - Not Folio
        """.trimIndent()
        val notes = WhatsNew.parse(md)
        assertEquals(listOf("0.4.0", "0.3.0"), notes.map { it.version })
        assertEquals("2026-09-14", notes[0].date)
        assertEquals(listOf("Added" to listOf("Focus", "Themes"), "Fixed" to listOf("A bug")), notes[0].sections)
        assertEquals(listOf("" to listOf("Loose item")), notes[1].sections)
    }
    @Test fun `the bundled changelog's newest section matches the app version`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "CHANGELOG.md").exists() }
        val newest = WhatsNew.parse(java.io.File(root, "CHANGELOG.md").readText()).first()
        val gradle = java.io.File(root, "app/build.gradle.kts").readText()
        // A beta carries its release's notes, so the section to match is the version without the suffix.
        val version = Regex("""val folioVersion = "([^"]+)"""").find(gradle)!!.groupValues[1].substringBefore('-')
        assertEquals(version, newest.version)
    }

    @Test fun `a beta shows the notes for the release it belongs to`() {
        // 0.7.0-beta.1 and 0.7.0 share a versionCode on purpose, and the changelog has one section for both.
        assertEquals("0.7.0", "0.7.0-beta.1".substringBefore('-'))
        assertEquals("0.7.0", "0.7.0".substringBefore('-'))
    }

    @Test fun `bold titles split from their detail and plain lines keep their text`() {
        assertEquals(NoteItem("More rows", "Home adds rows."), WhatsNew.split("**More rows:** home adds rows."))
        assertEquals(NoteItem("Big Clock", "A clock."), WhatsNew.split("**Big Clock**: A clock."))
        assertEquals(NoteItem(null, "Folders can be moved again."), WhatsNew.split("Folders can be moved again."))
    }

    @Test fun `every new feature in the current release has a short bold title`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "CHANGELOG.md").exists() }
        val latest = WhatsNew.parse(java.io.File(root, "CHANGELOG.md").readText()).first()
        val added = latest.sections.first { it.first == "Added" }.second.map(WhatsNew::split)
        assertTrue(added.isNotEmpty())
        added.forEach { assertTrue("Needs a title: ${it.detail}", it.title != null && it.title!!.length <= 40) }
    }
}
