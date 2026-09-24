package com.mccal.folio.duo

/**
 * Fold8Duo: tells the last words of a close from the first word of an opening, on the front screen.
 *
 * After a fast close the panels swap to the cover while the engine is still reporting the hinge. The public sensor's
 * "0" lands first, so the engine's late last word (7° at 18:13:49 on 2026-09-22) looked like the hinge rising from 0
 * and started a front-screen opening that nothing followed ("abandoned: no swap after 1206 ms"). A late word above the
 * closed band's 12° did the same through the other opening check (15° at 18:16:07, 13° at 18:17:55), and a late word
 * kept as the tracker's angle cut short a real opening's start at 05:14:48. Measured against the engine's own previous
 * word instead, every one of them was still falling: the tail of the close.
 *
 * So a word is the tail when it is no higher than the engine's word before it and that word came moments ago; and on
 * the front screen with the phone already shut ([dropOnCover]) the tail is dropped whole. A real opening a second
 * later, or after any rest, is judged afresh; one that starts within the window lower than the close's last word is only
 * delayed by a word, and the system's own opening signal does not pass through here at all.
 *
 * Pure: no android.*, no clock of its own. HingeFeed passes every engine word, in order, with its arrival time.
 */
internal class ClosingTail(private val windowMs: Long = WINDOW_MS) {
    private var lastWord = Float.NaN
    private var lastAt = 0L

    /** Records [degrees] (arrived at [nowMs]) and returns true when it is the tail of a close, not the hinge rising. */
    fun isTail(degrees: Float, nowMs: Long): Boolean {
        val tail = !lastWord.isNaN() && nowMs - lastAt in 0..windowMs && degrees <= lastWord
        lastWord = degrees
        lastAt = nowMs
        return tail
    }

    companion object {
        /** The engine's words are 30–250 ms apart while the hinge moves; a word a second after the last is a new move. */
        const val WINDOW_MS = 1_000L

        /**
         * A tail word reaching the front screen ([expanded] false) once the phone reads shut ([closed]) is dropped
         * before anything sees it: it must not start an opening, and must not become the angle the next word is
         * compared with.
         */
        fun dropOnCover(tail: Boolean, expanded: Boolean, closed: Boolean) = tail && !expanded && closed
    }
}
