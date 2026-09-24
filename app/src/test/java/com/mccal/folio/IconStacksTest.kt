package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Test

class IconStacksTest {
    @Test fun `stacks add and remove apps, cap their size and drop when empty`() {
        var stacks = IconStacks.toggle(emptyMap(), "mail", "slack")
        stacks = IconStacks.toggle(stacks, "mail", "teams")
        assertEquals(listOf("slack", "teams"), stacks["mail"])
        assertEquals(stacks, IconStacks.toggle(stacks, "mail", "mail")) // an app can't stack on itself
        stacks = IconStacks.toggle(IconStacks.toggle(stacks, "mail", "slack"), "mail", "teams")
        assertEquals(emptyMap<String, List<String>>(), stacks)
        val full = (1..10).fold(emptyMap<String, List<String>>()) { s, i -> IconStacks.toggle(s, "a", "app$i") }
        assertEquals(IconStacks.MAX, full.getValue("a").size)
    }

    @Test fun `pruning removes uninstalled apps and empty stacks`() {
        val stacks = mapOf("mail" to listOf("slack", "gone"), "gone" to listOf("slack"), "maps" to listOf("gone"))
        assertEquals(mapOf("mail" to listOf("slack")), IconStacks.prune(stacks, setOf("mail", "slack", "maps")))
    }
}
