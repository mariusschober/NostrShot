package com.nostrpost.app.signing

import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.nostr.Hex
import com.nostrpost.app.nostr.NostrCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-26: parse secrets into bytes, with no extra hex string and wipeable output. */
class KeyImportTest {

    private val privHex = "0000000000000000000000000000000000000000000000000000000000000001"
    private val expectedPub = NostrCrypto.pubkeyFromPriv(privHex)

    @Test
    fun hexImportProducesBytesAndPubkey() {
        val result = KeyImport.parse(privHex) as KeyImport.Result.Ok
        try {
            assertArrayEquals(Hex.decode(privHex), result.secret)
            assertEquals(expectedPub, result.pubkeyHex)
        } finally {
            result.secret.fill(0)
        }
    }

    @Test
    fun nsecImportProducesBytesAndPubkey() {
        val nsec = Bech32.encode("nsec", Hex.decode(privHex))
        val result = KeyImport.parse(nsec) as KeyImport.Result.Ok
        try {
            assertArrayEquals(Hex.decode(privHex), result.secret)
            assertEquals(expectedPub, result.pubkeyHex)
        } finally {
            result.secret.fill(0)
        }
    }

    @Test
    fun invalidInputsReturnErrorsWithoutEchoingTheSecret() {
        val inputs = listOf(
            "",
            "nsec1invalidchecksum",
            "deadbeef",
            "zz".repeat(32),
            "0000000000000000000000000000000000000000000000000000000000000000"
        )
        inputs.forEach { input ->
            val result = KeyImport.parse(input)
            assertTrue("'$input' should fail", result is KeyImport.Result.Error)
            val message = (result as KeyImport.Result.Error).message
            assertFalse("error must not echo the input", message.contains(input) && input.isNotBlank())
        }
    }

    @Test
    fun callerCanZeroTheSecretBuffer() {
        val result = KeyImport.parse(privHex) as KeyImport.Result.Ok
        result.secret.fill(0)
        assertTrue(result.secret.all { it == 0.toByte() })
    }
}
