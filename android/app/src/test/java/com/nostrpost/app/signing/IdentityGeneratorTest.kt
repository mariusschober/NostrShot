package com.nostrpost.app.signing

import com.nostrpost.app.nostr.Bech32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** NOSTRSHOT-29: a new identity is a valid, unique 32-byte key with a derived npub. */
class IdentityGeneratorTest {

    @Test
    fun generatedIdentityIsValidAndConsistent() {
        val identity = IdentityGenerator.generate()
        try {
            assertEquals(32, identity.secret.size)
            assertFalse(identity.secret.all { it == 0.toByte() })
            assertEquals(64, identity.pubkeyHex.length)
            val npub = Bech32.toNpub(identity.pubkeyHex)
            assertTrue(npub.startsWith("npub1"))
            assertEquals(
                identity.pubkeyHex,
                com.nostrpost.app.nostr.Hex.encode(Bech32.decodeNpub(npub))
            )
            val nsec = Bech32.encode("nsec", identity.secret)
            assertArrayEquals(identity.secret, Bech32.decodeNsec(nsec))
        } finally {
            identity.secret.fill(0)
        }
    }

    @Test
    fun twoIdentitiesDiffer() {
        val a = IdentityGenerator.generate(SecureRandom())
        val b = IdentityGenerator.generate(SecureRandom())
        try {
            assertFalse(a.secret.contentEquals(b.secret))
            assertFalse(a.pubkeyHex == b.pubkeyHex)
        } finally {
            a.secret.fill(0)
            b.secret.fill(0)
        }
    }
}
