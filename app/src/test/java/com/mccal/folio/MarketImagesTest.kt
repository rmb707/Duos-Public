package com.mccal.folio

import com.mccal.folio.market.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketImagesTest {
    private val source = Source("https://folio.example/source/", kind = Source.Kind.ADDED)

    @Test fun `pictures only ever come from the source's own host`() {
        assertEquals("https://folio.example/source/shots/1.png", MarketImages.urlFor(source, "shots/1.png"))
        assertEquals("https://folio.example/cdn/1.png", MarketImages.urlFor(source, "https://folio.example/cdn/1.png"))
        // Anywhere else would tell a third party who opened which package.
        assertNull(MarketImages.urlFor(source, "https://tracker.example/1.png"))
        assertNull(MarketImages.urlFor(source, "https://folio.example@tracker.example/1.png"))
        assertNull(MarketImages.urlFor(source, "http://folio.example/1.png"))
        assertNull(MarketImages.urlFor(Source("built-in", kind = Source.Kind.BUILT_IN), "shots/1.png"))
    }
}
