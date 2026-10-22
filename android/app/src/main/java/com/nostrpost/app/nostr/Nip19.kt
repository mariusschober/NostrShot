package com.nostrpost.app.nostr

import java.io.ByteArrayOutputStream

/**
 * Minimal NIP-19 encoders for sharing a published note.
 * Only `note` and `nevent` are produced; no private data is involved.
 */
object Nip19 {

    private const val TLV_ID = 0
    private const val TLV_RELAY = 1
    private const val TLV_AUTHOR = 2
    private const val TLV_KIND = 3

    fun note(eventIdHex: String): String = Bech32.encode("note", Hex.decode(eventIdHex))

    fun nevent(
        eventIdHex: String,
        relays: List<String> = emptyList(),
        authorHex: String? = null,
        kind: Int? = null
    ): String {
        val out = ByteArrayOutputStream()
        out.write(tlv(TLV_ID, Hex.decode(eventIdHex)))
        relays.filter { it.isNotBlank() }.forEach { relay ->
            out.write(tlv(TLV_RELAY, relay.toByteArray(Charsets.UTF_8)))
        }
        if (authorHex != null) out.write(tlv(TLV_AUTHOR, Hex.decode(authorHex)))
        if (kind != null) {
            out.write(
                tlv(
                    TLV_KIND,
                    byteArrayOf(
                        (kind ushr 24).toByte(),
                        (kind ushr 16).toByte(),
                        (kind ushr 8).toByte(),
                        kind.toByte()
                    )
                )
            )
        }
        return Bech32.encode("nevent", out.toByteArray())
    }

    fun njumpUrl(nevent: String): String = "https://njump.me/$nevent"

    private fun tlv(type: Int, value: ByteArray): ByteArray {
        require(value.size <= 0xFFFF) { "TLV value too long" }
        return byteArrayOf(
            type.toByte(),
            (value.size ushr 8).toByte(),
            value.size.toByte()
        ) + value
    }
}
