package com.mccal.folio

import java.text.Normalizer

/**
 * Fold8Duo: the App Library's A–Z list. Which letter an app is filed under, the order of the sections (# last, as on
 * iPhone), the letters down the index strip, and where each section starts in the list. Pure, so it runs as a JVM test.
 */
internal object LibraryIndex {
    const val OTHER = "#"
    private val LATIN = ('A'..'Z').map(Char::toString)

    /**
     * The section a label files under: its first letter with any accent taken off (É → E), or # for digits and symbols.
     * A Chinese name files under its pinyin's first letter, the way upstream's App Library does ([Pinyin.heading]).
     */
    fun letterOf(label: String): String {
        val first = label.trimStart().firstOrNull()?.takeIf(Char::isLetter) ?: return OTHER
        if (Character.UnicodeScript.of(first.code) == Character.UnicodeScript.HAN) return Pinyin.heading(label.trimStart())
        return Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar().toString()
    }

    /** Sections in list order: A–Z, then letters from other alphabets, then #. Apps keep the order they arrive in. */
    fun <T> sections(apps: List<T>, label: (T) -> String): List<Pair<String, List<T>>> =
        apps.groupBy { letterOf(label(it)) }.entries
            .sortedWith(compareBy<Map.Entry<String, List<T>>>({ rank(it.key) }, { it.key }))
            .map { it.key to it.value }

    private fun rank(letter: String) = when {
        letter == OTHER -> 2
        letter in LATIN -> 0
        else -> 1
    }

    /** The index strip: A to Z, then any other alphabet's letters that have apps, then #. */
    fun strip(present: Collection<String>): List<String> =
        LATIN + present.filter { rank(it) == 1 }.distinct().sorted() + OTHER

    /** Where [letter] on the strip takes the list: its own section, else the next one down, else the last one above. */
    fun target(letter: String, strip: List<String>, present: Set<String>): String? {
        val at = strip.indexOf(letter)
        if (at < 0) return null
        return strip.subList(at, strip.size).firstOrNull { it in present } ?: strip.subList(0, at).lastOrNull { it in present }
    }

    /** The strip entry under a finger [y] px down a strip [height] px tall. Past either end holds the end entry. */
    fun slotAt(y: Float, height: Float, count: Int): Int =
        if (count <= 0 || height <= 0f) 0 else (y / height * count).toInt().coerceIn(0, count - 1)

    /**
     * What the strip draws in [rows] rows: every entry when they fit, otherwise evenly spaced letters with a dot (null)
     * between each, keeping the first and last, the way iPhone shortens its index on a short screen. The finger still
     * reads the whole strip ([slotAt] over every entry), so a dot lands on the letters it stands for.
     */
    fun shown(strip: List<String>, rows: Int): List<String?> {
        if (rows <= 0 || strip.isEmpty()) return emptyList()
        if (strip.size <= rows) return strip
        val letters = (rows + 1) / 2
        if (letters <= 1) return listOf(strip.first())
        val picks = (0 until letters).map { strip[Math.round(it * (strip.size - 1f) / (letters - 1))] }.distinct()
        return buildList { picks.forEachIndexed { i, letter -> if (i > 0) add(null); add(letter) } }
    }

    /** Apps per row: one at phone width, more across the unfolded screen, each row at least [minRowDp] wide. */
    fun columns(widthDp: Float, minRowDp: Float = 240f, gapDp: Float = 12f): Int =
        ((widthDp + gapDp) / (minRowDp + gapDp)).toInt().coerceIn(1, 4)

    /** List items one section takes: its sticky heading plus a row per [columns] apps. */
    fun itemsIn(apps: Int, columns: Int): Int {
        val perRow = columns.coerceAtLeast(1)
        return 1 + (apps + perRow - 1) / perRow
    }

    /**
     * The list index of each section's heading, for sections of the given sizes. The sections are the last thing in the
     * list, so this counts back from the end ([total] items in all) and stays right whatever sits above them (a paused
     * work profile, downloads, a search's web row).
     */
    fun headingIndices(sizes: List<Pair<String, Int>>, columns: Int, total: Int): Map<String, Int> {
        var index = total - sizes.sumOf { itemsIn(it.second, columns) }
        return buildMap { sizes.forEach { (letter, count) -> put(letter, index); index += itemsIn(count, columns) } }
    }
}
