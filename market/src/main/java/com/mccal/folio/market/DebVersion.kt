package com.mccal.folio.market

/**
 * A package version with dpkg ordering: `[epoch:]upstream[-revision]`.
 *
 * - `~` sorts before everything, even the end of the string, so `1.0~beta1` < `1.0`.
 * - Letters sort before other symbols, and digit runs compare as numbers of any length (no overflow).
 * - A missing epoch is `0`, and a missing revision equals `0`.
 *
 * Two versions dpkg calls equal (`1.0` and `1.00`, `1.0` and `0:1.0-0`) are equal here too, with the same hash.
 */
class DebVersion private constructor(val text: String, private val epoch: String, private val upstream: String, private val revision: String) :
    Comparable<DebVersion> {

    override fun compareTo(other: DebVersion): Int {
        val byEpoch = compareParts(epoch, other.epoch)
        if (byEpoch != 0) return byEpoch
        val byUpstream = compareParts(upstream, other.upstream)
        if (byUpstream != 0) return byUpstream
        return compareParts(revision, other.revision)
    }

    override fun equals(other: Any?) = other is DebVersion && compareTo(other) == 0
    override fun hashCode() = listOf(normalize(epoch), normalize(upstream), normalize(revision)).hashCode()
    override fun toString() = text

    companion object {
        const val MAX_LENGTH = 64
        private val PATTERN = Regex("^(?:([0-9]+):)?([0-9][A-Za-z0-9.+~]*)(?:-([A-Za-z0-9.+~]+))?$")

        /** Null unless [text] matches the format's version pattern (the same one the schemas use). */
        fun parse(text: String): DebVersion? {
            if (text.length > MAX_LENGTH) return null
            val match = PATTERN.matchEntire(text) ?: return null
            val (epoch, upstream, revision) = match.destructured
            return DebVersion(text, epoch, upstream, revision)
        }

        private fun order(c: Char): Int = when {
            c.isAsciiDigit() -> 0
            c in 'A'..'Z' || c in 'a'..'z' -> c.code
            c == '~' -> -1
            else -> c.code + 256
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'

        /** dpkg's verrevcmp. */
        internal fun compareParts(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length || j < b.length) {
                while ((i < a.length && !a[i].isAsciiDigit()) || (j < b.length && !b[j].isAsciiDigit())) {
                    val ac = if (i < a.length) order(a[i]) else 0
                    val bc = if (j < b.length) order(b[j]) else 0
                    if (ac != bc) return ac.compareTo(bc)
                    i++
                    j++
                }
                while (i < a.length && a[i] == '0') i++
                while (j < b.length && b[j] == '0') j++
                var firstDiff = 0
                while (i < a.length && a[i].isAsciiDigit() && j < b.length && b[j].isAsciiDigit()) {
                    if (firstDiff == 0) firstDiff = a[i].compareTo(b[j])
                    i++
                    j++
                }
                if (i < a.length && a[i].isAsciiDigit()) return 1
                if (j < b.length && b[j].isAsciiDigit()) return -1
                if (firstDiff != 0) return firstDiff.coerceIn(-1, 1)
            }
            return 0
        }

        /**
         * A form where parts that compare equal are the same string: digit runs lose their leading zeros, and a final
         * all-zero run is dropped (dpkg treats `1.0` like `1.`, but `a0b` differs from `ab`).
         */
        private fun normalize(part: String): String {
            val out = StringBuilder()
            var k = 0
            var trailingZeroRun = -1
            while (k < part.length) {
                if (!part[k].isAsciiDigit()) { out.append(part[k]); k++; trailingZeroRun = -1; continue }
                val start = out.length
                while (k < part.length && part[k] == '0') k++
                while (k < part.length && part[k].isAsciiDigit()) { out.append(part[k]); k++ }
                trailingZeroRun = if (out.length == start) start else -1
                out.append('#') // ends the run, so "1.2" and "12." stay apart
            }
            if (trailingZeroRun >= 0) out.setLength(trailingZeroRun)
            return out.toString()
        }
    }
}
