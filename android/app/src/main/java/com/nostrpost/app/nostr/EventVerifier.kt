package com.nostrpost.app.nostr

object EventVerifier {

    /**
     * Verify that an externally signed event is *exactly* the event this app asked for.
     *
     * NIP-55 signers are untrusted input: a malicious signer can return a correctly signed event
     * with extra tags (`p`, `e`, `t`), a different timestamp or different content. Only the exact
     * unsigned event the app created may be published.
     */
    fun verify(signed: SignedEvent, expected: UnsignedEvent): Boolean {
        if (!signed.pubkey.equals(expected.pubkey, ignoreCase = true)) return false
        if (signed.createdAt != expected.createdAt) return false
        if (signed.kind != expected.kind) return false
        if (signed.tags != expected.tags) return false
        if (signed.content != expected.content) return false
        val expectedId = EventFactory.computeId(EventFactory.canonicalJson(expected))
        if (!expectedId.equals(signed.id, ignoreCase = true)) return false
        return NostrCrypto.verify(signed.id, signed.pubkey, signed.sig)
    }

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
