package com.nostrpost.app.signing

import com.nostrpost.app.nostr.EventFactory
import com.nostrpost.app.nostr.Hex
import com.nostrpost.app.nostr.NostrCrypto
import com.nostrpost.app.nostr.SignedEvent
import com.nostrpost.app.nostr.UnsignedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-07: a NIP-55 response is accepted only if it matches the request exactly. */
class SignerVerificationTest {

    private val priv = "0000000000000000000000000000000000000000000000000000000000000001"
    private val pub = NostrCrypto.pubkeyFromPriv(priv)
    private val expected = UnsignedEvent(pub, 1_700_000_000, 1, emptyList(), "hello")

    private fun sign(unsigned: UnsignedEvent): SignedEvent {
        val id = EventFactory.computeId(EventFactory.canonicalJson(unsigned))
        val sig = NostrCrypto.sign(Hex.decode(id), Hex.decode(priv))
        return EventFactory.createSigned(unsigned, sig)
    }

    @Test
    fun exactResponseWithMatchingSignatureIsAccepted() {
        val signed = sign(expected)
        assertNull(SignerVerification.errorFor(signed, expected, signed.sig))
    }

    @Test
    fun responseWithExtraTagsIsRejected() {
        val signed = sign(expected.copy(tags = listOf(listOf("t", "spam"))))
        val error = SignerVerification.errorFor(signed, expected, signed.sig)
        assertTrue(error != null && error.contains("tags"))
    }

    @Test
    fun responseWithChangedTimestampIsRejected() {
        val signed = sign(expected.copy(createdAt = expected.createdAt - 3600))
        val error = SignerVerification.errorFor(signed, expected, signed.sig)
        assertTrue(error != null && error.contains("timestamp"))
    }

    @Test
    fun mismatchedReturnedSignatureIsRejected() {
        val signed = sign(expected)
        val error = SignerVerification.errorFor(signed, expected, "00".repeat(64))
        assertTrue(error != null && error.contains("signature", ignoreCase = true))
    }

    @Test
    fun unsignedResponseWithoutSignatureIsRejected() {
        val signed = sign(expected).copy(sig = "00".repeat(64))
        val error = SignerVerification.errorFor(signed, expected, null)
        assertEquals("Signer returned an invalid event", error)
    }
}
