package com.nostrpost.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps Nostr private key (32 bytes) with AES/GCM/NoPadding key stored in AndroidKeyStore.
 * Ciphertext stored as file `nostr_secret.enc` = iv(12) || ciphertext+tag(32+16).
 *
 * StrongBox attempted first, fallback to TEE.
 */
class AndroidKeystoreSecretStore(private val context: Context) : SecretStore {

    companion object {
        private const val TAG = "KeystoreSecret"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "nostr_wrap_key"
        private const val FILE_NAME = "nostr_secret.enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): Pair<SecretKey, String> {
        if (keyStore.containsAlias(ALIAS)) {
            try {
                val key = keyStore.getKey(ALIAS, null) as SecretKey
                return Pair(key, "unknown")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get existing key, recreating: ${e.message}")
                try { keyStore.deleteEntry(ALIAS) } catch (_: Exception) {}
            }
        }
        // Check StrongBox feature
        val hasStrongBox = try {
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } catch (_: Exception) { false }
        var lastException: Exception? = null
        val attempts = mutableListOf<Pair<Boolean,String>>()
        if (hasStrongBox) attempts.add(Pair(true, "StrongBox"))
        attempts.add(Pair(false, "Hardware"))
        for ((useStrongBox, label) in attempts) {
            try {
                val key = generateKey(useStrongBox)
                Log.i(TAG, "Created wrapping key with $label (hasStrongBox=$hasStrongBox)")
                return Pair(key, label)
            } catch (e: StrongBoxUnavailableException) {
                Log.w(TAG, "StrongBox unavailable, fallback: ${e.message}")
                lastException = e
                continue
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create key with $label: ${e.message}", e)
                lastException = e
                // clean up alias if partially created
                try { if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS) } catch (_: Exception) {}
                if (useStrongBox) continue else throw e
            }
        }
        throw lastException ?: IllegalStateException("Unable to create wrapping key")
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            // No user authentication to keep flow fast (device-unlocked sufficient). StrongBox/TEE still protects at rest.
            // If biometric per-sign is desired later, we could set setUserAuthenticationRequired(true)
            .setUserAuthenticationRequired(false)

        if (useStrongBox) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            } else {
                throw StrongBoxUnavailableException("StrongBox requires API 28+", null)
            }
        }

        // Invalidation on new biometric enrollment not needed since no auth required; omit.

        keyGen.init(builder.build())
        return keyGen.generateKey()
    }

    private fun getKey(): SecretKey {
        if (!keyStore.containsAlias(ALIAS)) {
            val (k, _) = getOrCreateKey()
            return k
        }
        return keyStore.getKey(ALIAS, null) as SecretKey
    }

    override suspend fun hasSecret(): Boolean {
        val f = File(context.filesDir, FILE_NAME)
        return f.exists() && f.length() > IV_BYTES
    }

    override suspend fun storeSecret(privKey32: ByteArray, protectionLevelOut: ((String) -> Unit)?) {
        require(privKey32.size == 32)
        val (key, level) = try {
            getOrCreateKey()
        } catch (e: Exception) {
            // If key already exists, use existing
            Pair(getKey(), "Hardware")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12 bytes
        require(iv.size == IV_BYTES) { "Unexpected IV size ${iv.size}" }
        val ciphertext = cipher.doFinal(privKey32) // includes GCM tag
        val out = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(out, 0)
        ciphertext.copyInto(out, iv.size)
        val file = File(context.filesDir, FILE_NAME)
        file.writeBytes(out)
        // Ensure not exposed: private filesDir is already private (MODE_PRIVATE)
        // Store protection level via prefs optionally
        protectionLevelOut?.invoke(level)
        // Clear input? caller manages
    }

    override suspend fun loadSecret(): ByteArray {
        val file = File(context.filesDir, FILE_NAME)
        require(file.exists()) { "No secret stored" }
        val all = file.readBytes()
        require(all.size > IV_BYTES + 16) { "Corrupted secret file" }
        val iv = all.copyOfRange(0, IV_BYTES)
        val ciphertext = all.copyOfRange(IV_BYTES, all.size)
        val key = getKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    override suspend fun clearSecret() {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            // Overwrite before delete if possible? Simple delete for now
            try { file.delete() } catch (_: Exception) { file.writeBytes(ByteArray(0)); file.delete() }
        }
        // Optionally delete key from keystore
        try {
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete keystore entry: ${e.message}")
        }
    }

    override suspend fun getProtectionLevel(): String? {
        // We do not query keystore for hardware backing directly; infer via KeyInfo
        return try {
            val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return null
            // Use KeyFactory? For AES we can try to get KeyInfo via SecretKeyFactory
            val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.isInsideSecureHardware && info.securityLevel == android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                info.isInsideSecureHardware -> "Hardware-backed"
                else -> "Software"
            }
        } catch (_: Exception) { null }
    }
}
