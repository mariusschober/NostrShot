package com.nostrpost.app.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-07: external signer responses must match the exact unsigned event, not just content. */
class EventVerifierTest {

    private val priv = "0000000000000000000000000000000000000000000000000000000000000001"
    private val pub = NostrCrypto.pubkeyFromPriv(priv)

    private fun sign(unsigned: UnsignedEvent): SignedEvent {
        val id = EventFactory.computeId(EventFactory.canonicalJson(unsigned))
        val sig = NostrCrypto.sign(Hex.decode(id), Hex.decode(priv))
        return EventFactory.createSigned(unsigned, sig)
    }

    private fun unsigned(
        content: String = "hello",
        createdAt: Long = 1_700_000_000,
        tags: List<List<String>> = emptyList()
    ) = UnsignedEvent(pub, createdAt, 1, tags, content)

    @Test
    fun exactEventIsAccepted() {
        val expected = unsigned()
        assertTrue(EventVerifier.verify(sign(expected), expected))
    }

    @Test
    fun extraTagsAreRejectedEvenWithAValidSignature() {
        val expected = unsigned()
        val withTags = unsigned(tags = listOf(listOf("p", "victim")))
        val signed = sign(withTags) // internally consistent & validly signed
        assertTrue(EventVerifier.verify(signed)) // old shape-only check still passes
        assertFalse("signer must not add tags", EventVerifier.verify(signed, expected))
    }

    @Test
    fun changedTimestampIsRejectedEvenWithAValidSignature() {
        val expected = unsigned(createdAt = 1_700_000_000)
        val backdated = unsigned(createdAt = 1_600_000_000)
        assertFalse(EventVerifier.verify(sign(backdated), expected))
    }

    @Test
    fun changedContentIsRejected() {
        val expected = unsigned(content = "hello")
        val tampered = unsigned(content = "hello world")
        assertFalse(EventVerifier.verify(sign(tampered), expected))
    }

    @Test
    fun changedKindIsRejected() {
        val expected = unsigned()
        val wrongKind = expected.copy(kind = 4)
        assertFalse(EventVerifier.verify(sign(wrongKind), expected))
    }

    @Test
    fun changedPubkeyIsRejected() {
        val expected = unsigned()
        val other = expected.copy(pubkey = "11".repeat(32))
        assertFalse(EventVerifier.verify(sign(other), expected))
    }

    @Test
    fun tamperedSignatureIsRejected() {
        val expected = unsigned()
        val signed = sign(expected)
        val tampered = signed.copy(sig = "00".repeat(64))
        assertFalse(EventVerifier.verify(tampered, expected))
    }

    @Test
    fun tamperedIdIsRejected() {
        val expected = unsigned()
        val signed = sign(expected)
        assertFalse(EventVerifier.verify(signed.copy(id = "22".repeat(32)), expected))
    }
}
