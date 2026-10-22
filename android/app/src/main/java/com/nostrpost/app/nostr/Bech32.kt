package com.nostrpost.app.nostr

/**
 * Minimal Bech32 implementation for NIP-19 (nsec/npub).
 * Based on BIP-173.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.apply {
        for (i in CHARSET.indices) {
            this[CHARSET[i].code] = i
        }
    }
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff shl 5) xor v
            for (i in 0..4) {
                if ((top shr i) and 1 != 0) chk = chk xor GEN[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code shr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        val exp = hrpExpand(hrp)
        val combined = IntArray(exp.size + data.size)
        exp.copyInto(combined, 0)
        data.copyInto(combined, exp.size)
        return polymod(combined) == 1
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val exp = hrpExpand(hrp)
        val values = IntArray(exp.size + data.size + 6)
        exp.copyInto(values, 0)
        data.copyInto(values, exp.size)
        // 6 zeros already
        val polymod = polymod(values) xor 1
        val ret = IntArray(6)
        for (i in 0..5) ret[i] = (polymod shr (5 * (5 - i))) and 31
        return ret
    }

    fun encode(hrp: String, data: ByteArray): String {
        // Convert 8-bit to 5-bit
        val converted = convertBits(data, 8, 5, true)
        val checksum = createChecksum(hrp, converted)
        val combined = IntArray(converted.size + checksum.size)
        converted.copyInto(combined, 0)
        checksum.copyInto(combined, converted.size)
        val sb = StringBuilder()
        sb.append(hrp)
        sb.append('1')
        for (b in combined) sb.append(CHARSET[b])
        return sb.toString()
    }

    fun decode(bech: String): Pair<String, ByteArray> {
        val lower = bech.lowercase()
        // Bech32 must be lowercase or uppercase consistently; we lowercased.
        val pos = lower.lastIndexOf('1')
        require(pos >= 1) { "Invalid bech32: missing separator" }
        require(pos + 7 <= lower.length) { "Invalid bech32: too short" }
        // NIP-19 nevent strings legitimately exceed BIP-173's 90-char limit when relay hints
        // are included, so allow a generous bound instead of rejecting valid note links.
        require(lower.length <= 4096) { "Invalid bech32: too long" }
        val hrp = lower.substring(0, pos)
        val dataPart = lower.substring(pos + 1)
        val data = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val c = dataPart[i]
            require(c.code < 128) { "Invalid char" }
            val v = CHARSET_REV[c.code]
            require(v != -1) { "Invalid charset char: $c" }
            data[i] = v
        }
        require(verifyChecksum(hrp, data)) { "Invalid checksum" }
        val payload = data.copyOf(data.size - 6)
        val decoded = convertBits(payload, 5, 8, false)
        return Pair(hrp, decoded)
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        val ints = IntArray(data.size) { data[it].toInt() and 0xFF }
        return convertBitsInts(ints, fromBits, toBits, pad)
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        val ints = convertBitsInts(data.map { it }.toIntArray(), fromBits, toBits, pad)
        return ByteArray(ints.size) { ints[it].toByte() }
    }

    private fun convertBitsInts(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            require(value ushr fromBits == 0) { "Invalid value" }
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) ret.add((acc shl (toBits - bits)) and maxv)
        } else {
            require(bits < fromBits && (acc shl (toBits - bits) and maxv) == 0) { "Invalid padding" }
        }
        return ret.toIntArray()
    }

    // NIP-19 helpers
    fun decodeNsec(nsec: String): ByteArray {
        val (hrp, data) = decode(nsec.trim())
        require(hrp == "nsec") { "Expected nsec, got $hrp" }
        require(data.size == 32) { "nsec must be 32 bytes, got ${data.size}" }
        return data
    }

    fun encodeNpub(pubkeyHex: String): String {
        val bytes = Hex.decode(pubkeyHex)
        require(bytes.size == 32)
        return encode("npub", bytes)
    }

    fun decodeNpub(npub: String): ByteArray {
        val (hrp, data) = decode(npub.trim())
        require(hrp == "npub") { "Expected npub, got $hrp" }
        require(data.size == 32)
        return data
    }

    fun toNpub(pubkeyHex: String): String = encodeNpub(pubkeyHex)
}
