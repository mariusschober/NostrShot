package com.nostrpost.app.signing

import com.nostrpost.app.nostr.NostrCrypto
import java.security.SecureRandom

/** Generates a fresh Nostr identity locally; the secret never leaves the device. */
object IdentityGenerator {

    class GeneratedIdentity(
        val secret: ByteArray,
        val pubkeyHex: String
    )

    fun generate(random: SecureRandom = SecureRandom()): GeneratedIdentity {
        val secret = ByteArray(32)
        random.nextBytes(secret)
        return try {
            GeneratedIdentity(secret, NostrCrypto.pubkeyFromPrivBytes(secret))
        } catch (e: Exception) {
            secret.fill(0)
            throw e
        }
    }
}
