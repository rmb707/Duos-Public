package com.mccal.folio

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Markdown subset `format-v1.md` promises a package's page, and the promise that nothing else is touched. */
class MarketTextTest {

    @Test fun `a blank line starts a new paragraph, and wrapped lines join back up`() {
        val paragraphs = MarketText.paragraphs("One line\nwrapped by the author.\n\nA second paragraph.")
        assertEquals(2, paragraphs.size)
        assertEquals("One line wrapped by the author.", paragraphs[0].text.text)
        assertEquals("A second paragraph.", paragraphs[1].text.text)
        assertTrue(paragraphs.none { it.bullet })
    }

    @Test fun `each list line is its own bullet`() {
        val paragraphs = MarketText.paragraphs("What it does:\n\n- One thing\n- Another thing")
        assertEquals(listOf(false, true, true), paragraphs.map { it.bullet })
        assertEquals("One thing", paragraphs[1].text.text)
        assertEquals("Another thing", paragraphs[2].text.text)
    }

    @Test fun `bold and italic are styles, not characters`() {
        val text = MarketText.inline("Warm icons for **2,400** apps, *mostly*.")
        assertEquals("Warm icons for 2,400 apps, mostly.", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("2,400", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("mostly", text.text.substring(italic.start, italic.end))
    }

    @Test fun `a marker with nothing to close it stays as it was typed`() {
        assertEquals("2 * 3 is 6", MarketText.inline("2 * 3 is 6").text)
        assertEquals("**almost bold", MarketText.inline("**almost bold").text)
        assertTrue(MarketText.inline("2 * 3 is 6").spanStyles.isEmpty())
    }

    @Test fun `a link becomes a link`() {
        val text = MarketText.inline("Read the [guide](https://folio.mccal.dev/guide) first.")
        assertEquals("Read the guide first.", text.text)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://folio.mccal.dev/guide", (link.item as LinkAnnotation.Url).url)
        assertEquals("guide", text.text.substring(link.start, link.end))
    }

    @Test fun `anything but https is left as text`() {
        for (url in listOf("http://example.com", "javascript:alert(1)", "folio://package/x", "/etc/passwd")) {
            val text = MarketText.inline("Open [this]($url) now.")
            assertEquals("Open [this]($url) now.", text.text)
            assertTrue("$url should not be a link", text.getLinkAnnotations(0, text.length).isEmpty())
        }
    }

    @Test fun `no headings, no images, no html`() {
        val page = "# Heading\n\n![shot](x.png)\n\n<b>bold</b> & <script>x</script>"
        val paragraphs = MarketText.paragraphs(page)
        assertEquals("# Heading", paragraphs[0].text.text)
        assertEquals("![shot](x.png)", paragraphs[1].text.text)
        assertEquals("<b>bold</b> & <script>x</script>", paragraphs[2].text.text)
        assertTrue(paragraphs.all { it.text.getLinkAnnotations(0, it.text.length).isEmpty() })
    }

    @Test fun `the real pages read as plain paragraphs`() {
        // Folio's own packages are written without markers, so nothing in them should be reinterpreted.
        val cabinet = "Flick up on an app icon and a small panel opens over the Home screen - above the icon " +
            "where there is room for it.\n\nThe gesture only claims a clear upward flick."
        val paragraphs = MarketText.paragraphs(cabinet)
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs.all { it.text.spanStyles.isEmpty() })
        assertNull(paragraphs.firstOrNull { it.bullet })
    }
}
