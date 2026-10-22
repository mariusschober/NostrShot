package com.nostrpost.app.nostr

/**
 * Minimal, dependency-free parser for NIP-01 relay messages.
 *
 * Relay input is untrusted, so parsing never throws: malformed frames return null and are ignored.
 * Only the `["OK", <id>, <bool>, <reason>?]` shape is interpreted; every other frame is ignored.
 */
internal object RelayMessageParser {

    data class Ok(val id: String, val accepted: Boolean, val reason: String)

    fun parseOk(raw: String): Ok? = try {
        val value = JsonReader(raw).readValue()
        val arr = value as? List<*> ?: return null
        if (arr.size < 3) return null
        if ((arr[0] as? String) != "OK") return null
        val id = arr[1] as? String ?: return null
        val accepted = arr[2] as? Boolean ?: return null
        val reason = arr.getOrNull(3) as? String ?: ""
        Ok(id, accepted, reason)
    } catch (_: Exception) {
        null
    }

    private class JsonReader(private val text: String) {
        private var index = 0

        fun readValue(): Any? {
            skipWhitespace()
            if (index >= text.length) throw IllegalArgumentException("unexpected end")
            return when (val c = text[index]) {
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                '{' -> readObjectSkipped()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("unexpected ${'$'}c")
            }
        }

        private fun readArray(): List<Any?> {
            index++ // '['
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return out
            }
            while (true) {
                out.add(readValue())
                skipWhitespace()
                if (index >= text.length) throw IllegalArgumentException("unterminated array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return out }
                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        private fun readString(): String {
            index++ // opening quote
            val sb = StringBuilder()
            while (index < text.length) {
                when (val c = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("bad escape")
                        when (val esc = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("bad unicode")
                                val hex = text.substring(index, index + 4)
                                sb.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("bad escape ${'$'}esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun readNumber(): String {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] in "-+.eE")) index++
            if (start == index) throw IllegalArgumentException("bad number")
            return text.substring(start, index)
        }

        /** Skips a nested object/array without interpreting it (e.g. future relay metadata). */
        private fun readObjectSkipped(): String {
            val start = index
            var depth = 0
            var inString = false
            while (index < text.length) {
                val c = text[index]
                if (inString) {
                    if (c == '\\') index++ else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) {
                                index++
                                return text.substring(start, index)
                            }
                        }
                    }
                }
                index++
            }
            throw IllegalArgumentException("unterminated object")
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, index)) throw IllegalArgumentException("expected ${'$'}literal")
            index += literal.length
        }
    }
}
