package com.nostrpost.app.nostr

object EventVerifier {

    fun verify(signed: SignedEvent, expectedContent: String? = null, expectedPubkey: String? = null): Boolean {
        if (signed.kind != 1) return false
        if (expectedContent != null && signed.content != expectedContent) return false
        if (expectedPubkey != null && signed.pubkey.lowercase() != expectedPubkey.lowercase()) return false
        // Recompute id
        val unsigned = UnsignedEvent(
            pubkey = signed.pubkey,
            createdAt = signed.createdAt,
            kind = signed.kind,
            tags = signed.tags,
            content = signed.content
        )
        val canonical = EventFactory.canonicalJson(unsigned)
        val expectedId = EventFactory.computeId(canonical)
        if (expectedId.lowercase() != signed.id.lowercase()) return false
        // Verify sig
        if (!NostrCrypto.verify(signed.id, signed.pubkey, signed.sig)) return false
        return true
    }

    fun verifyJson(json: String, expectedContent: String? = null, expectedPubkey: String? = null): Boolean {
        return try {
            val ev = EventFactory.signedEventFromJson(json)
            verify(ev, expectedContent, expectedPubkey)
        } catch (_: Exception) { false }
    }
}
