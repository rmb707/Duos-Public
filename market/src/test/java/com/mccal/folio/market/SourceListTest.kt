package com.mccal.folio.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sources the user added: kept in order, https only, and survive a restart. */
class SourceListTest {
    private val store = MemoryStore()
    private val list = SourceList(store)

    @Test fun `sources are kept in the order they were added`() {
        assertTrue(list.added().isEmpty())
        assertTrue(list.add(Source("https://folio.mccal.dev/community/", addedAt = 1)))
        assertTrue(list.add(Source("https://maya.example/folio/", addedAt = 2)))
        assertEquals(
            listOf("https://folio.mccal.dev/community/", "https://maya.example/folio/"),
            list.added().map { it.url },
        )
    }

    @Test fun `a trailing slash doesn't make a second source`() {
        list.add(Source("https://maya.example/folio"))
        list.add(Source("https://maya.example/folio/", name = "Maya"))
        assertEquals(1, list.added().size)
        assertEquals("Maya", list.added().single().name)
    }

    @Test fun `an insecure source is refused, unless it's a local one for development`() {
        assertTrue(!list.add(Source("http://maya.example/folio/")))
        assertTrue(list.added().isEmpty())
        assertTrue(list.add(Source("http://localhost:8787/", kind = Source.Kind.LOCAL_DEV)))
        assertEquals(Source.Kind.LOCAL_DEV, list.added().single().kind)
    }

    @Test fun `a source's own name is remembered, and removing forgets it`() {
        list.add(Source("https://maya.example/folio/"))
        list.rename("https://maya.example/folio", "Maya's packages")
        assertEquals("Maya's packages", list.added().single().name)
        assertEquals("Maya's packages", list.added().single().label)
        list.remove("https://maya.example/folio/")
        assertTrue(list.added().isEmpty())
    }

    @Test fun `a source with no name reads as its address`() {
        list.add(Source("https://maya.example/folio/"))
        assertEquals("maya.example/folio", list.added().single().label)
    }

    @Test fun `the list survives a restart, and unreadable data is treated as empty`() {
        list.add(Source("https://maya.example/folio/", name = "Maya"))
        assertEquals("Maya", SourceList(store).added().single().name)
        store.set("market:sources", "not json")
        assertTrue(SourceList(store).added().isEmpty())
    }
}
