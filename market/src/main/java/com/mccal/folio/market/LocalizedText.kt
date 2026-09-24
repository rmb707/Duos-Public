package com.mccal.folio.market

import java.util.Locale

/**
 * A text field from a package: either one string, or translations keyed by language tag with an `en` fallback.
 * A plain string is stored under `en`.
 */
class LocalizedText private constructor(private val values: Map<String, String>) {
    val english: String get() = values.getValue("en")
    val languages: Set<String> get() = values.keys

    /**
     * The best string for [preferred] language tags, most preferred first (e.g. `LocaleList.getDefault().toLanguageTags()`
     * split on commas). Tries the exact tag, then the bare language, then any regional variant of it, then English.
     */
    fun resolve(preferred: List<String>): String {
        for (tag in preferred) {
            val wanted = tag.trim().lowercase(Locale.ROOT)
            if (wanted.isEmpty()) continue
            values[wanted]?.let { return it }
            val language = wanted.substringBefore('-')
            values[language]?.let { return it }
            values.entries.firstOrNull { it.key.substringBefore('-') == language }?.let { return it.value }
        }
        return english
    }

    override fun equals(other: Any?) = other is LocalizedText && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = if (values.size == 1) english else values.toString()

    companion object {
        const val MAX_LANGUAGES = 64
        private val TAG = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

        fun of(english: String) = LocalizedText(mapOf("en" to english))

        /**
         * Reads a JSON value (a String or an org.json JSONObject). Returns null and reports through [problem] when it
         * isn't valid text; each string must be 1..[maxLength] characters (code points).
         */
        internal fun read(value: Any?, maxLength: Int, problem: (String) -> Unit): LocalizedText? {
            fun fits(text: String) = text.codePointCount(0, text.length) in 1..maxLength
            return when (value) {
                is String -> if (fits(value)) of(value) else null.also { problem("must be 1–$maxLength characters") }
                is org.json.JSONObject -> {
                    val keys = value.keys().asSequence().toList()
                    val map = LinkedHashMap<String, String>()
                    var ok = true
                    if (keys.size > MAX_LANGUAGES) { problem("has more than $MAX_LANGUAGES languages"); ok = false }
                    if ("en" !in keys) { problem("needs an \"en\" entry"); ok = false }
                    for (key in keys) {
                        val text = value.opt(key)
                        when {
                            !TAG.matches(key) -> { problem("\"${key.take(20)}\" isn't a language tag like en or pt-BR"); ok = false }
                            text !is String || !fits(text) -> { problem("$key must be 1–$maxLength characters"); ok = false }
                            // Tags are matched without case; two keys that differ only in case are ambiguous.
                            map.put(key.lowercase(Locale.ROOT), text) != null -> { problem("repeats language $key"); ok = false }
                        }
                    }
                    if (ok) LocalizedText(map) else null
                }
                else -> null.also { problem("must be a string or an object of translations") }
            }
        }
    }
}
