package com.nostrpost.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the Nostr private key (32 bytes) with an AES/GCM/NoPadding key stored in AndroidKeyStore.
 * Ciphertext is stored as `nostr_secret.enc` = iv(12) || ciphertext+tag(32+16).
 *
 * StrongBox is attempted first, with a TEE fallback.
 *
 * Invariant: reading an existing secret never creates, replaces or deletes the wrapping key.
 * If the key is missing or invalid the store raises [SecretUnavailableException] and the UI must
 * ask the user to re-import their nsec. Only [clearSecret] (an explicit "remove key") deletes it.
 */
class AndroidKeystoreSecretStore internal constructor(
    private val keyProvider: WrappingKeyProvider,
    private val secretFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SecretStore {

    constructor(context: Context) : this(
        AndroidWrappingKeyProvider(context),
        File(context.filesDir, FILE_NAME)
    )

    companion object {
        private const val TAG = "KeystoreSecret"
        internal const val FILE_NAME = "nostr_secret.enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12

        const val REIMPORT_MESSAGE =
            "Local key unavailable — the Android Keystore key is gone or invalid. Re-import your nsec."
    }

    override suspend fun hasSecret(): Boolean = withContext(ioDispatcher) {
        try {
            secretFile.exists() && secretFile.length() > IV_BYTES
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun storeSecret(
        privKey32: ByteArray,
        protectionLevelOut: ((String) -> Unit)?
    ): Unit = withContext(ioDispatcher) {
        require(privKey32.size == 32) { "Private key must be 32 bytes" }
        val (key, level) = try {
            keyProvider.getOrCreateKey()
        } catch (e: SecretUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.IO,
                "Could not create the Android Keystore wrapping key.",
                e
            )
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected IV size ${iv.size}" }
        val ciphertext = cipher.doFinal(privKey32) // includes GCM tag
        val out = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(out, 0)
        ciphertext.copyInto(out, iv.size)
        try {
            writeAtomically(out)
        } finally {
            ciphertext.fill(0)
            out.fill(0)
        }
        protectionLevelOut?.invoke(level)
    }

    /** Write to a sibling temp file, fsync it, then atomically replace the previous ciphertext. */
    private fun writeAtomically(bytes: ByteArray) {
        val dir = secretFile.parentFile ?: throw SecretUnavailableException(
            SecretUnavailableException.Reason.IO,
            "Secret directory unavailable."
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.IO,
                "Secret directory unavailable."
            )
        }
        val tmp = File(dir, "${secretFile.name}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    secretFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                // Some filesystems do not support atomic moves; renameTo still replaces in place.
                if (!tmp.renameTo(secretFile)) {
                    throw SecretUnavailableException(
                        SecretUnavailableException.Reason.IO,
                        "Could not store the encrypted key."
                    )
                }
            }
        } catch (e: SecretUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.IO,
                "Could not store the encrypted key.",
                e
            )
        } finally {
            try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
        }
    }

    override suspend fun loadSecret(): ByteArray = withContext(ioDispatcher) {
        if (!secretFile.exists()) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.KEY_MISSING,
                REIMPORT_MESSAGE
            )
        }
        val all = try {
            secretFile.readBytes()
        } catch (e: Exception) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.IO,
                "Could not read the encrypted key.",
                e
            )
        }
        if (all.size <= IV_BYTES + GCM_TAG_BITS / 8) {
            all.fill(0)
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.CORRUPT,
                REIMPORT_MESSAGE
            )
        }
        val iv = all.copyOfRange(0, IV_BYTES)
        val ciphertext = all.copyOfRange(IV_BYTES, all.size)

        val key = try {
            keyProvider.getExistingKey()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.KEY_INVALIDATED,
                REIMPORT_MESSAGE,
                e
            )
        } catch (e: SecretUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.KEY_MISSING,
                REIMPORT_MESSAGE,
                e
            )
        } ?: throw SecretUnavailableException(
            SecretUnavailableException.Reason.KEY_MISSING,
            REIMPORT_MESSAGE
        )

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.KEY_INVALIDATED,
                REIMPORT_MESSAGE,
                e
            )
        } catch (e: AEADBadTagException) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.CORRUPT,
                REIMPORT_MESSAGE,
                e
            )
        } catch (e: Exception) {
            throw SecretUnavailableException(
                SecretUnavailableException.Reason.CORRUPT,
                REIMPORT_MESSAGE,
                e
            )
        } finally {
            all.fill(0)
        }
    }

    override suspend fun clearSecret(): Unit = withContext(ioDispatcher) {
        if (secretFile.exists()) {
            try {
                secretFile.delete()
            } catch (_: Exception) {
                try {
                    secretFile.writeBytes(ByteArray(0))
                    secretFile.delete()
                } catch (_: Exception) {}
            }
        }
        // Explicit removal is the only path allowed to delete the wrapping key.
        try {
            keyProvider.deleteKey()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete keystore entry: ${e.message}")
        }
    }

    override suspend fun getProtectionLevel(): String? = withContext(ioDispatcher) {
        try {
            val key = keyProvider.peekKey() ?: return@withContext null
            keystoreProtectionLevelOf(key)
        } catch (_: Exception) {
            null
        }
    }
}

/** Maps a wrapping key to the user-visible protection level, without ever creating a key. */
internal fun keystoreProtectionLevelOf(key: SecretKey): String {
    return try {
        val factory = javax.crypto.SecretKeyFactory.getInstance(
            key.algorithm,
            AndroidWrappingKeyProvider.ANDROID_KEYSTORE
        )
        val info = factory.getKeySpec(
            key,
            android.security.keystore.KeyInfo::class.java
        ) as android.security.keystore.KeyInfo
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.isInsideSecureHardware &&
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        protectionLevelLabel(info.isInsideSecureHardware, strongBox)
    } catch (_: Exception) {
        "Unknown"
    }
}

/** Pure mapping so the reported security claim can be unit-tested. */
internal fun protectionLevelLabel(insideSecureHardware: Boolean, strongBox: Boolean): String = when {
    insideSecureHardware && strongBox -> "StrongBox"
    insideSecureHardware -> "Hardware-backed"
    else -> "Software"
}

/**
 * Abstraction over the AndroidKeyStore wrapping key, so the read/create/delete invariants can be
 * unit-tested with a fake provider (the real AndroidKeyStore is not available on the JVM).
 */
internal interface WrappingKeyProvider {
    /** Returns the existing key, or null when no key is stored. Never creates one. */
    fun getExistingKey(): SecretKey?

    /** Returns the existing key without surfacing errors. Never creates one. */
    fun peekKey(): SecretKey?

    /** Returns the existing key, or creates one only when none exists. */
    fun getOrCreateKey(): Pair<SecretKey, String>

    fun deleteKey()
}

internal class AndroidWrappingKeyProvider(private val context: Context) : WrappingKeyProvider {

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TAG = "KeystoreKey"
        private const val ALIAS = "nostr_wrap_key"
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun getExistingKey(): SecretKey? {
        if (!keyStore.containsAlias(ALIAS)) return null
        return keyStore.getKey(ALIAS, null) as? SecretKey
    }

    override fun peekKey(): SecretKey? = try {
        getExistingKey()
    } catch (_: Exception) {
        null
    }

    override fun getOrCreateKey(): Pair<SecretKey, String> {
        val existing = getExistingKey()
        if (existing != null) {
            return Pair(existing, keystoreProtectionLevelOf(existing))
        }
        // Only create when the alias truly does not exist. A failed read above propagates and
        // must never be "fixed" by deleting an existing key.
        if (keyStore.containsAlias(ALIAS)) {
            val key = keyStore.getKey(ALIAS, null) as? SecretKey
                ?: throw SecretUnavailableException(
                    SecretUnavailableException.Reason.KEY_MISSING,
                    AndroidKeystoreSecretStore.REIMPORT_MESSAGE
                )
            return Pair(key, keystoreProtectionLevelOf(key))
        }
        return createKey()
    }

    private fun createKey(): Pair<SecretKey, String> {
        val hasStrongBox = try {
            context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
            )
        } catch (_: Exception) {
            false
        }
        var lastException: Exception? = null
        val attempts = mutableListOf<Pair<Boolean, String>>()
        if (hasStrongBox) attempts.add(Pair(true, "StrongBox"))
        attempts.add(Pair(false, "Hardware-backed"))
        for ((useStrongBox, label) in attempts) {
            try {
                val key = generateKey(useStrongBox)
                Log.i(TAG, "Created wrapping key with $label (hasStrongBox=$hasStrongBox)")
                return Pair(key, keystoreProtectionLevelOf(key))
            } catch (e: StrongBoxUnavailableException) {
                Log.w(TAG, "StrongBox unavailable, fallback: ${e.message}")
                lastException = e
                continue
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create key with $label: ${e.message}", e)
                lastException = e
                // Only clean up a partially-created key when this call created the alias.
                try {
                    if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
                } catch (_: Exception) {}
                if (useStrongBox) continue else throw e
            }
        }
        throw SecretUnavailableException(
            SecretUnavailableException.Reason.KEY_MISSING,
            "Could not create a wrapping key.",
            lastException
        )
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            // No user authentication to keep the flow fast (device-unlocked sufficient).
            .setUserAuthenticationRequired(false)

        if (useStrongBox) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            } else {
                throw StrongBoxUnavailableException("StrongBox requires API 28+", null)
            }
        }
        keyGen.init(builder.build())
        return keyGen.generateKey()
    }

    override fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }
}
