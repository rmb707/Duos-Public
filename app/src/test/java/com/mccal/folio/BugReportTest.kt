package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

class BugReportTest {
    @Test fun `fills the bug form with the version, phone and Android`() {
        assertEquals("https://github.com/rmb707/Duos-Public/issues/new?template=bug_report.yml&version=0.4.0" +
            "&phone=samsung+SM-F971U&android=17+%28API+37%29", BugReport.url("0.4.0", "samsung", "SM-F971U", "17", 37))
    }

    @Test fun `doesn't repeat the maker and encodes everything`() {
        assertEquals("https://github.com/rmb707/Duos-Public/issues/new?template=bug_report.yml&version=" +
            "&phone=Google+Pixel+%26+Co&android=16+%28API+36%29", BugReport.url(null, "Google", "Google Pixel & Co", "16", 36))
    }

    @Test fun `adds the screen summary to the diagnostics field`() {
        assertEquals(true, BugReport.url("0.6.1", "samsung", "SM-F966U", "16", 36, "folded 411×891 dp")
            .endsWith("&diagnostics=Screen%3A+folded+411%C3%97891+dp%0A%0A"))
    }
}
