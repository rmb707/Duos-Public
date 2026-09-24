package com.mccal.folio

/**
 * How far an install has got, and roughly how much longer it will take.
 *
 * The App Store draws a ring that fills; Folio does the same, and adds a line of words because a ring alone doesn't
 * say whether to wait or put the phone down. The words are deliberately vague - "a few seconds left" rather than
 * "7 seconds left" - because an estimate from a few hundred milliseconds of a mobile connection is a guess, and a
 * precise-looking guess that turns out wrong is worse than an honest one.
 */
internal data class MarketProgress(
    val phase: Phase,
    val bytes: Long = 0,
    val total: Long = -1,
    /** When the download started, for working out the rate. */
    val startedAt: Long = 0,
    val now: Long = 0,
) {
    enum class Phase {
        /** Bytes are arriving, so there is something real to measure. */
        DOWNLOADING,

        /** Checked and being applied: file writes, not bytes over a network, and quick. */
        APPLYING,
    }

    /** 0 to 1 while downloading, or null when the source didn't say how big the file is. */
    val fraction: Float?
        get() = when {
            phase != Phase.DOWNLOADING || total <= 0 -> null
            else -> (bytes.toFloat() / total).coerceIn(0f, 1f)
        }

    /**
     * What to say underneath. Null once there's nothing useful to add - a ring that's nearly full says it better
     * than words do.
     */
    val wording: Wording?
        get() {
            if (phase == Phase.APPLYING) return Wording.Applying
            val remaining = remainingSeconds ?: return sizeSoFar
            return when {
                remaining <= 2 -> Wording.NearlyDone
                remaining < 10 -> Wording.AFewSeconds
                remaining < 90 -> Wording.Seconds(((remaining + 5) / 10) * 10)
                else -> Wording.Minutes(((remaining + 30) / 60).toInt())
            }
        }

    /** Megabytes, while there's no estimate worth making yet. */
    private val sizeSoFar: Wording?
        get() = if (total <= 0) null else Wording.Megabytes(mb(bytes), mb(total))

    /**
     * What [wording] says, before it is words. Kept apart from the strings so this class stays plain Kotlin that a
     * test can run without Android, and so every language gets its own sentence - and its own plural rules, which
     * "minute${"$"}{if (n > 1) "s" else ""}" only ever got right in English.
     */
    sealed interface Wording {
        data object Applying : Wording
        data object NearlyDone : Wording
        data object AFewSeconds : Wording
        data class Seconds(val seconds: Long) : Wording
        data class Minutes(val minutes: Int) : Wording
        data class Megabytes(val soFar: String, val total: String) : Wording
    }

    /**
     * Seconds left at the rate so far, or null for the first second - a rate measured over a moment of a mobile
     * connection says nothing, and "about 4 minutes left" that becomes "nearly done" is worse than no estimate.
     */
    private val remainingSeconds: Long?
        get() {
            val elapsed = now - startedAt
            if (total <= 0 || bytes <= 0 || elapsed < 1_000) return null
            val rate = bytes.toDouble() / elapsed // bytes per millisecond
            if (rate <= 0) return null
            return (((total - bytes) / rate) / 1000).toLong().coerceAtLeast(0)
        }

    private fun mb(value: Long) = String.format("%.1f", value / 1_048_576.0)
}
