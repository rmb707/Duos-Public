package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStyleTest {
    @Test fun `page icons scale inside their cell and labels follow Home unless set`() {
        val g = homeGeometry(475f, 700f, LayoutPreset(), true)
        assertEquals(g.iconSize to true, PageStyle().apply(g, true))
        val (small, _) = PageStyle(iconScale = .82f).apply(g, true)
        assertEquals(g.iconSize * .82f, small, .01f)
        val (large, labels) = PageStyle(iconScale = 1.3f, labels = false).apply(g, true)
        assertTrue(large <= g.cellWidth - 8f && large <= g.rowHeight - 8f)
        assertEquals(false, labels)
        assertTrue(PageStyle().isDefault)
    }
}
