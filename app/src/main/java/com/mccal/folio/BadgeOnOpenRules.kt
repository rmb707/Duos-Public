package com.mccal.folio

/**
 * Fold8Duo: "Clear badges when opened" (WP-48). Opening an app from Folio takes its badge off the way the long-press
 * menu's Clear Badge does (BadgeClears in LiveIcons.kt): the notifications stay in the shade and only stop counting.
 * A notification with something new to say puts the badge back. Pure, so it runs as a JVM test; BadgesOnOpen.kt feeds
 * it Folio's notifications.
 *
 * Why not BadgeClears itself: it remembers bare keys for as long as Folio runs. That is fine for a menu item used now
 * and then, but opening apps clears all the time, and apps post again under the same key: a chat's notification is
 * cancelled when you read it and comes back under the same key with the next message. Remembered by key alone, that
 * chat would never badge again. Here a key is forgotten as its notification goes, and a re-post that says something
 * different counts again.
 */
internal object BadgeOnOpenRules {
    /** One notification as badges see it. [content] fingerprints its title and text; [postTime] is Android's. */
    data class Note(val key: String, val packageName: String, val postTime: Long, val clearable: Boolean, val content: Int)

    /** A notification as it was when its app was opened. */
    data class Seen(val postTime: Long, val content: Int)

    /** Opening [packageName]: each of its notifications showing now stops counting toward its badge. */
    fun open(seen: Map<String, Seen>, notes: List<Note>, packageName: String): Map<String, Seen> {
        val mine = notes.filter { it.packageName == packageName }
        if (mine.isEmpty()) return seen
        return seen + mine.associate { it.key to Seen(it.postTime, it.content) }
    }

    /**
     * The keys that don't count toward a badge: seen when their app was opened, and not posted again since with
     * something new to say. An ongoing notification (music, a download, directions) stays cleared however often it
     * updates, as with Clear Badge. One you could swipe away counts again once it's re-posted with a different title
     * or text: another message in the same conversation, a second missed call.
     */
    fun cleared(notes: List<Note>, seen: Map<String, Seen>): Set<String> {
        if (seen.isEmpty()) return emptySet()
        return notes.mapNotNullTo(HashSet()) { note ->
            val then = seen[note.key] ?: return@mapNotNullTo null
            note.key.takeIf { !note.clearable || note.postTime <= then.postTime || note.content == then.content }
        }
    }

    /**
     * Forgets the notifications that have gone, so one posted again later under the same key is new and badges again
     * even when it says exactly what it said before. [notes] must be the full list of what's showing.
     */
    fun forgetGone(seen: Map<String, Seen>, notes: List<Note>): Map<String, Seen> {
        if (seen.isEmpty()) return seen
        val showing = notes.mapTo(HashSet()) { it.key }
        return if (seen.keys.all { it in showing }) seen else seen.filterKeys { it in showing }
    }
}
