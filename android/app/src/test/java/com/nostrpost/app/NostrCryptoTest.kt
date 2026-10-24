package com.nostrpost.app

import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.nostr.EventFactory
import com.nostrpost.app.nostr.EventVerifier
import com.nostrpost.app.nostr.Hex
import com.nostrpost.app.nostr.NostrCrypto
import org.junit.Assert.*
import org.junit.Test

class NostrCryptoTest {

    // Known test vector: private  0000000000000000000000000000000000000000000000000000000000000001 ?
    // Let's use a generated key but deterministic for testing.
    // Example from nostr spec: secret 32 bytes of 0x11...? Use known nsec from docs
    // We'll test roundtrip.

    @Test
    fun testBech32NsecRoundtrip() {
        val hex = "0000000000000000000000000000000000000000000000000000000000000001"
        // derive nsec manually? We need to test encode/decode
        val bytes = Hex.decode(hex)
        val nsec = Bech32.encode("nsec", bytes)
        assertTrue(nsec.startsWith("nsec1"))
        val decoded = Bech32.decodeNsec(nsec)
        assertEquals(hex, Hex.encode(decoded))
    }

    @Test
    fun testNpubRoundtrip() {
        val hex = "d34c6b1a94a5c1e5a3b9a7e0c8f5e6d7c8b9a0c1d2e3f4a5b6c7d8e9f0a1b2c3"
        // Not a valid pubkey but bech32 should still roundtrip
        val bytes = Hex.decode(hex)
        val npub = Bech32.toNpub(hex)
        assertTrue(npub.startsWith("npub1"))
        val decoded = Bech32.decodeNpub(npub)
        assertEquals(hex, Hex.encode(decoded))
    }

    @Test
    fun testPubkeyDerivation() {
        val priv = "0000000000000000000000000000000000000000000000000000000000000001"
        val pub = NostrCrypto.pubkeyFromPriv(priv)
        assertEquals(64, pub.length)
        assertTrue(pub.matches(Regex("[0-9a-f]{64}")))
        // Deterministic
        val pub2 = NostrCrypto.pubkeyFromPriv(priv)
        assertEquals(pub, pub2)
        // Known vector: priv 1 => pub is G x=79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".lowercase(), pub)
    }

    @Test
    fun testEventSigningAndVerification() {
        val priv = "0000000000000000000000000000000000000000000000000000000000000001"
        val pub = NostrCrypto.pubkeyFromPriv(priv)
        val content = "Hello Nostr Post"
        val unsigned = EventFactory.createUnsigned(pub, content)
        val canonical = EventFactory.canonicalJson(unsigned)
        val id = EventFactory.computeId(canonical)
        assertEquals(64, id.length)
        val sig = NostrCrypto.sign(Hex.decode(id), Hex.decode(priv))
        assertEquals(128, sig.length)
        val signed = EventFactory.createSigned(unsigned, sig)
        assertTrue(EventVerifier.verify(signed, expectedContent = content, expectedPubkey = pub))
        // Tamper content should fail
        val tampered = signed.copy(content = "tampered")
        assertFalse(EventVerifier.verify(tampered, expectedContent = content))
        assertFalse(EventVerifier.verify(tampered))
        // But tampered with its own content should also fail because id mismatch
        assertFalse(EventVerifier.verify(tampered, expectedContent = "tampered"))
    }

    @Test
    fun testCanonicalSerialization() {
        // Ensure deterministic serialization
        val pub = "a".repeat(64)
        val ev = com.nostrpost.app.nostr.UnsignedEvent(pub, 1234567890L, 1, emptyList(), "test\nwith \"quotes\"")
        val json = EventFactory.canonicalJson(ev)
        // Expected: [0,"aaa...",1234567890,1,[],"test\nwith \"quotes\""]
        assertTrue(json.startsWith("""[0,"$pub",1234567890,1,[],""""))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\\""))
        val id1 = EventFactory.computeId(json)
        val id2 = EventFactory.computeId(json)
        assertEquals(id1, id2)
    }

    @Test
    fun testInvalidNsecRejected() {
        try {
            Bech32.decodeNsec("nsec1invalidchecksum")
            fail("Should throw")
        } catch (_: Exception) {
            // expected
        }
        assertFalse(NostrCrypto.isValidPrivHex("zzzz"))
        assertFalse(NostrCrypto.isValidPrivHex("deadbeef"))
    }

    @Test
    fun testPubHexRequiresExactly64HexCharacters() {
        val valid = "0123456789abcdef".repeat(4)
        assertEquals(64, valid.length)

        assertTrue(NostrCrypto.isValidPubHex(valid))
        assertTrue(NostrCrypto.isValidPubHex(valid.uppercase()))

        assertFalse(NostrCrypto.isValidPubHex(valid.dropLast(1)))
        assertFalse(NostrCrypto.isValidPubHex(valid + "0"))

        assertFalse(NostrCrypto.isValidPubHex(valid.replaceRange(0, 1, "g")))
        assertFalse(NostrCrypto.isValidPubHex(valid.replaceRange(0, 1, "z")))

        assertFalse(NostrCrypto.isValidPubHex(" $valid"))
        assertFalse(NostrCrypto.isValidPubHex("$valid "))
        assertFalse(NostrCrypto.isValidPubHex(valid.replaceRange(10, 11, " ")))
        assertFalse(NostrCrypto.isValidPubHex(""))
        assertFalse(NostrCrypto.isValidPubHex(" ".repeat(64)))
    }

    @Test
    fun testSignLocallyHelper() {
        val goodPriv = "0000000000000000000000000000000000000000000000000000000000000001"
        val pub = NostrCrypto.pubkeyFromPriv(goodPriv)
        val signed = EventFactory.signLocally(pub, "test", goodPriv)
        assertTrue(EventVerifier.verify(signed, expectedContent = "test", expectedPubkey = pub))
        val signed2 = EventFactory.signLocally(pub, "test2", goodPriv)
        assertTrue(EventVerifier.verify(signed2))
    }
}
