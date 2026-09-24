package com.mccal.folio.market

/**
 * Strict RFC 8259 check that runs before org.json sees a file.
 *
 * Android's org.json is lenient (comments, unquoted keys, single quotes, trailing text) and keeps the last of two
 * duplicate keys, while the schema tools reject or read those differently. Checking here keeps Folio, the schemas and
 * `folio-pkg` in agreement, and caps nesting so a hostile file can't overflow org.json's recursive reader.
 */
internal object JsonGuard {
    const val MAX_DEPTH = 32

    /** Returns null when [text] is one strict JSON object within the limits, otherwise the reason it isn't. */
    fun check(text: String, maxChars: Int): String? {
        if (text.length > maxChars) return "file is larger than ${maxChars / 1024} KB"
        return try {
            Scanner(text).run()
            null
        } catch (e: Reject) {
            e.message
        }
    }

    private class Reject(message: String) : Exception(message)

    private class Scanner(private val s: String) {
        private var i = 0

        fun run() {
            skipSpace()
            if (peek() != '{') fail("must be a JSON object")
            value(1)
            skipSpace()
            if (i != s.length) fail("unexpected text after the JSON object")
        }

        private fun fail(reason: String): Nothing = throw Reject("not valid JSON: $reason at character $i")

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c'")
            i++
        }

        private fun value(depth: Int) {
            if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH levels")
            skipSpace()
            when (peek()) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> fail("expected a value")
            }
        }

        private fun obj(depth: Int) {
            expect('{')
            val keys = HashSet<String>()
            skipSpace()
            if (peek() == '}') { i++; return }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("expected a quoted key")
                val key = string()
                if (!keys.add(key)) fail("duplicate key \"${key.take(40)}\"")
                skipSpace()
                expect(':')
                value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(depth: Int) {
            expect('[')
            skipSpace()
            if (peek() == ']') { i++; return }
            while (true) {
                value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = peek() ?: fail("unterminated string")
                i++
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> fail("control character in a string")
                    c != '\\' -> out.append(c)
                    else -> when (val e = peek() ?: fail("unterminated escape")) {
                        '"', '\\', '/' -> { out.append(e); i++ }
                        'b' -> { out.append('\b'); i++ }
                        'f' -> { out.append(''); i++ }
                        'n' -> { out.append('\n'); i++ }
                        'r' -> { out.append('\r'); i++ }
                        't' -> { out.append('\t'); i++ }
                        'u' -> {
                            if (i + 5 > s.length) fail("short \\u escape")
                            val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: fail("bad \\u escape")
                            if (s.substring(i + 1, i + 5).any { it == '+' || it == '-' }) fail("bad \\u escape")
                            out.append(code.toChar())
                            i += 5
                        }
                        else -> fail("bad escape")
                    }
                }
            }
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
        }

        private fun number() {
            if (peek() == '-') i++
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> digits()
                else -> fail("bad number")
            }
            if (peek() == '.') { i++; if (peek() !in '0'..'9') fail("bad number"); digits() }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') fail("bad number")
                digits()
            }
        }

        private fun digits() {
            while (peek() in '0'..'9') i++
        }
    }
}
