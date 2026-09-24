package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeOnOpenRulesTest {
    private val chat = "org.example.chat"
    private val mail = "org.example.mail"
    private val music = "org.example.music"

    private fun note(key: String, pkg: String, postTime: Long, text: String, clearable: Boolean = true) =
        BadgeOnOpenRules.Note(key, pkg, postTime, clearable, text.hashCode())

    /** What upstream's badge count shows once opening has cleared what it cleared. */
    private fun badges(notes: List<BadgeOnOpenRules.Note>, seen: Map<String, BadgeOnOpenRules.Seen>): Map<String, Int> {
        val cleared = BadgeOnOpenRules.cleared(notes, seen)
        return notes.filter { it.key !in cleared }.groupingBy { it.packageName }.eachCount()
    }

    @Test fun `opening an app clears its badge and no other app's`() {
        val showing = listOf(note("c1", chat, 100, "Alice: hi"), note("c2", chat, 110, "Bob: yo"), note("m1", mail, 120, "Invoice"))
        val seen = BadgeOnOpenRules.open(emptyMap(), showing, chat)
        assertEquals(setOf("c1", "c2"), seen.keys)
        assertEquals(mapOf(mail to 1), badges(showing, seen))
    }

    @Test fun `opening an app with nothing showing changes nothing`() {
        val seen = mapOf("x" to BadgeOnOpenRules.Seen(1, 1))
        assertSame(seen, BadgeOnOpenRules.open(seen, listOf(note("m1", mail, 120, "Invoice")), chat))
    }

    @Test fun `a new notification badges again`() {
        val seen = BadgeOnOpenRules.open(emptyMap(), listOf(note("c1", chat, 100, "Alice: hi")), chat)
        val later = listOf(note("c1", chat, 100, "Alice: hi"), note("c3", chat, 300, "Carol: lunch?"))
        assertEquals(mapOf(chat to 1), badges(later, seen))
    }

    @Test fun `a conversation's notification updated with a new message badges again`() {
        val seen = BadgeOnOpenRules.open(emptyMap(), listOf(note("c1", chat, 100, "Alice: hi")), chat)
        assertEquals(mapOf(chat to 1), badges(listOf(note("c1", chat, 400, "Alice: 2 new messages")), seen))
    }

    @Test fun `re-posted saying the same thing, or an ongoing one updating, stays cleared`() {
        val seen = BadgeOnOpenRules.open(emptyMap(),
            listOf(note("c1", chat, 100, "Alice: hi"), note("p1", music, 100, "Song A", clearable = false)), chat)
            .let { BadgeOnOpenRules.open(it, listOf(note("p1", music, 100, "Song A", clearable = false)), music) }
        val later = listOf(note("c1", chat, 500, "Alice: hi"), note("p1", music, 900, "Song B", clearable = false))
        assertEquals(emptyMap<String, Int>(), badges(later, seen))
    }

    @Test fun `gone and posted again under the same key is new, even saying the same`() {
        // A missed call from the same person, a day apart: the dialer reuses the key and the words.
        val call = note("d1", "org.example.dialer", 100, "Missed call")
        var seen = BadgeOnOpenRules.open(emptyMap(), listOf(call), call.packageName)
        seen = BadgeOnOpenRules.forgetGone(seen, emptyList())
        assertTrue(seen.isEmpty())
        assertEquals(mapOf(call.packageName to 1), badges(listOf(call.copy(postTime = 90_000)), seen))
    }

    @Test fun `forgetting keeps what's still showing and changes nothing when all of it is`() {
        val showing = listOf(note("c1", chat, 100, "Alice: hi"), note("c2", chat, 110, "Bob: yo"))
        val seen = BadgeOnOpenRules.open(emptyMap(), showing, chat)
        assertSame(seen, BadgeOnOpenRules.forgetGone(seen, showing))
        assertEquals(setOf("c2"), BadgeOnOpenRules.forgetGone(seen, showing.drop(1)).keys)
        val none = emptyMap<String, BadgeOnOpenRules.Seen>()
        assertSame(none, BadgeOnOpenRules.forgetGone(none, showing))
    }

    @Test fun `nothing opened, nothing cleared`() {
        assertEquals(emptySet<String>(), BadgeOnOpenRules.cleared(listOf(note("c1", chat, 100, "Alice: hi")), emptyMap()))
    }

    /** Through Folio's own notification type and upstream's badge count (BadgeClears), as MainActivity combines them. */
    @Test fun `with Folio's notifications and upstream's count`() {
        fun item(key: String, pkg: String, title: String, text: String, postTime: Long, clearable: Boolean = true) =
            NotificationItem(key, pkg, "App", null, title, text, postTime, clearable, null)
        val before = listOf(item("c1", chat, "Alice", "hi", 100), item("m1", mail, "Bank", "Statement", 120))
        val seen = BadgeOnOpenRules.open(emptyMap(), before.map { it.badgeNote() }, chat)
        val after = before + item("c2", chat, "Bob", "yo", 200)
        val clearBadgeTapped = emptySet<String>()
        val counts = BadgeClears.counts(after, clearBadgeTapped + BadgeOnOpenRules.cleared(after.map { it.badgeNote() }, seen))
        assertEquals(mapOf(chat to 1, mail to 1), counts)
        // Same words, new title (another sender in a group chat) counts as something new.
        val renamed = listOf(item("c1", chat, "Alice, Bob", "hi", 300))
        assertEquals(mapOf(chat to 1), BadgeClears.counts(renamed, BadgeOnOpenRules.cleared(renamed.map { it.badgeNote() }, seen)))
    }
}
