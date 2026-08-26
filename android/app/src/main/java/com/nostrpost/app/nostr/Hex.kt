package com.nostrpost.app.nostr

object Hex {
    private const val HEX_CHARS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var i = 0
        var j = 0
        while (i < bytes.size) {
            val v = bytes[i].toInt() and 0xFF
            out[j++] = HEX_CHARS[v ushr 4]
            out[j++] = HEX_CHARS[v and 0x0F]
            i++
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        val s = hex.trim().lowercase()
        require(s.length % 2 == 0) { "Invalid hex length" }
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = HEX_CHARS.indexOf(s[i * 2])
            val lo = HEX_CHARS.indexOf(s[i * 2 + 1])
            require(hi != -1 && lo != -1) { "Invalid hex char" }
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }

    fun isValidHex(s: String, expectedBytes: Int): Boolean {
        if (s.length != expectedBytes * 2) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
