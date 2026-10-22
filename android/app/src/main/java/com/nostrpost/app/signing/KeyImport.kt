package com.nostrpost.app.signing

import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.nostr.Hex
import com.nostrpost.app.nostr.NostrCrypto

/**
 * Parses an imported nsec/hex secret directly into bytes.
 *
 * The goal is to avoid creating extra immutable hex Strings of the private key. The caller owns
 * the returned [Ok.secret] and must zero it in a `finally` block after storing it.
 */
internal object KeyImport {

    sealed class Result {
        class Ok(val secret: ByteArray, val pubkeyHex: String) : Result()
        class Error(val message: String) : Result()
    }

    private val HEX64 = Regex("[0-9a-fA-F]{64}")

    fun parse(raw: String): Result {
        val input = raw.trim()
        if (input.isEmpty()) return Result.Error("Enter nsec1… or 64-char hex")
        if (input.startsWith("nsec1", ignoreCase = true)) {
            val bytes = try {
                Bech32.decodeNsec(input.lowercase())
            } catch (_: Exception) {
                return Result.Error("Invalid nsec — check the key and try again")
            }
            if (bytes.size != 32) {
                bytes.fill(0)
                return Result.Error("Invalid nsec — wrong key length")
            }
            return derive(bytes)
        }
        if (HEX64.matches(input)) {
            val bytes = try {
                Hex.decode(input)
            } catch (_: Exception) {
                return Result.Error("Invalid secret key")
            }
            if (bytes.size != 32) {
                bytes.fill(0)
                return Result.Error("Invalid secret key")
            }
            return derive(bytes)
        }
        return Result.Error("Enter nsec1… or 64-char hex")
    }

    private fun derive(bytes: ByteArray): Result {
        return try {
            val pubkey = NostrCrypto.pubkeyFromPrivBytes(bytes)
            Result.Ok(bytes, pubkey)
        } catch (_: Exception) {
            bytes.fill(0)
            Result.Error("Invalid secret key")
        }
    }
}
