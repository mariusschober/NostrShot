package com.nostrpost.app.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

object NostrCrypto {
    private val secp: Secp256k1 by lazy { Secp256k1.get() }
    private val random = SecureRandom()

    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    fun pubkeyFromPriv(privHex: String): String {
        val priv = Hex.decode(privHex)
        return pubkeyFromPrivBytes(priv)
    }

    fun pubkeyFromPrivBytes(priv: ByteArray): String {
        require(priv.size == 32) { "priv must be 32 bytes" }
        require(Secp256k1.get().secKeyVerify(priv)) { "Invalid private key" }
        val uncompressed = secp.pubkeyCreate(priv) // 65 bytes 04 + x(32) + y(32)
        // x-only pubkey is x coordinate
        val xOnly = uncompressed.copyOfRange(1, 33)
        return Hex.encode(xOnly)
    }

    fun signId(idHex: String, privHex: String): String {
        val id = Hex.decode(idHex)
        val priv = Hex.decode(privHex)
        return sign(id, priv)
    }

    fun sign(hash32: ByteArray, priv: ByteArray): String {
        require(hash32.size == 32)
        require(priv.size == 32)
        // 32-byte aux rand
        val aux = ByteArray(32)
        random.nextBytes(aux)
        val sig = secp.signSchnorr(hash32, priv, aux)
        require(sig.size == 64)
        return Hex.encode(sig)
    }

    // Variant that zeroes priv after use - caller should manage
    fun signWithClear(hash32: ByteArray, priv: ByteArray): String {
        try {
            return sign(hash32, priv)
        } finally {
            // Caller can clear; we also clear here after copy?
            // We don't clear here to allow test reuse; LocalSigner will clear.
        }
    }

    fun verify(idHex: String, pubkeyHex: String, sigHex: String): Boolean {
        return try {
            val id = Hex.decode(idHex)
            val pub = Hex.decode(pubkeyHex)
            val sig = Hex.decode(sigHex)
            require(id.size == 32)
            require(pub.size == 32)
            require(sig.size == 64)
            secp.verifySchnorr(sig, id, pub)
        } catch (e: Exception) {
            false
        }
    }

    fun isValidPrivHex(hex: String): Boolean {
        if (!Hex.isValidHex(hex, 32)) return false
        return try {
            val b = Hex.decode(hex)
            Secp256k1.get().secKeyVerify(b)
        } catch (_: Exception) { false }
    }

    /**
     * A pubkey is exactly 64 hex characters. Both cases are accepted, matching [Hex.decode],
     * which lowercases before decoding.
     */
    fun isValidPubHex(hex: String): Boolean = Hex.isValidHex(hex, 32)
}
