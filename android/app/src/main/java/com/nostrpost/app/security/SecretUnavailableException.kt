package com.nostrpost.app.security

/**
 * Typed failure raised by a [SecretStore] when the stored key cannot be used.
 *
 * Callers must treat this as "the local identity needs to be re-imported" rather than as a
 * generic signing failure, and must never silently create a replacement wrapping key: doing so
 * would orphan the existing ciphertext and permanently lose the identity.
 */
class SecretUnavailableException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class Reason {
        /** The Keystore wrapping key no longer exists (restore, reset, OS cleanup). */
        KEY_MISSING,

        /** The wrapping key exists but was invalidated by the OS (lock screen/Keystore reset). */
        KEY_INVALIDATED,

        /** The ciphertext exists but cannot be decrypted with the available key. */
        CORRUPT,

        /** Reading/writing the encrypted secret failed. */
        IO
    }
}
