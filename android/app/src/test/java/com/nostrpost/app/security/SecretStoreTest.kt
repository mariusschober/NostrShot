package com.nostrpost.app.security

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * NOSTRSHOT-02: a read must never create or delete the Keystore wrapping key, and all failure
 * modes must surface a typed [SecretUnavailableException] so the UI can offer "re-import nsec".
 */
class SecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyProvider : WrappingKeyProvider {
        var key: SecretKey? = null
        var createCount = 0
        var deleteCount = 0

        override fun getExistingKey(): SecretKey? = key

        override fun peekKey(): SecretKey? = key

        override fun getOrCreateKey(): Pair<SecretKey, String> {
            val existing = key ?: generate().also {
                key = it
                createCount++
            }
            return Pair(existing, "Software")
        }

        override fun deleteKey() {
            deleteCount++
            key = null
        }

        fun generateAnotherKey() {
            key = generate()
        }

        private fun generate(): SecretKey {
            val gen = KeyGenerator.getInstance("AES")
            gen.init(256)
            return gen.generateKey()
        }
    }

    private fun newStore(provider: FakeKeyProvider = FakeKeyProvider()): Pair<AndroidKeystoreSecretStore, File> {
        val file = File(tmp.root, "nostr_secret.enc")
        return Pair(AndroidKeystoreSecretStore(provider, file), file)
    }

    private fun assertUnavailable(reason: SecretUnavailableException.Reason, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected SecretUnavailableException($reason)")
        } catch (e: SecretUnavailableException) {
            assertEquals(reason, e.reason)
            assertNotNull(e.message)
            assertTrue("message should tell the user to re-import", e.message!!.contains("Re-import", true))
        }
    }

    @Test
    fun roundTripStoresAndLoadsSecret() = runBlocking {
        val provider = FakeKeyProvider()
        val (store, file) = newStore(provider)
        val secret = ByteArray(32) { i -> (i + 1).toByte() }
        store.storeSecret(secret) {}
        assertTrue(file.exists())
        assertArrayEquals(secret, store.loadSecret())
        assertEquals(1, provider.createCount)
        assertFalse(File(tmp.root, "nostr_secret.enc.tmp").exists())
        secret.fill(0)
    }

    @Test
    fun loadWithMissingKeyThrowsTypedErrorAndNeverCreatesAKey() = runBlocking {
        val provider = FakeKeyProvider()
        val (store, file) = newStore(provider)
        store.storeSecret(ByteArray(32) { 7 }) {}
        assertEquals(1, provider.createCount)
        // Simulate the wrapping key disappearing (restore/reset) while ciphertext remains.
        provider.key = null
        assertTrue(file.exists())

        assertUnavailable(SecretUnavailableException.Reason.KEY_MISSING) { store.loadSecret() }

        assertEquals("read must not create a replacement key", 1, provider.createCount)
        assertNull(provider.key)
        assertTrue("ciphertext must be preserved", file.exists())
    }

    @Test
    fun loadWithDifferentKeyThrowsCorruptAndPreservesCiphertext() = runBlocking {
        val provider = FakeKeyProvider()
        val (store, file) = newStore(provider)
        store.storeSecret(ByteArray(32) { 3 }) {}
        provider.generateAnotherKey()

        assertUnavailable(SecretUnavailableException.Reason.CORRUPT) { store.loadSecret() }
        assertEquals(1, provider.createCount)
        assertTrue(file.exists())
    }

    @Test
    fun corruptedCiphertextThrowsTypedError() = runBlocking {
        val provider = FakeKeyProvider()
        val (store, file) = newStore(provider)
        store.storeSecret(ByteArray(32) { 9 }) {}
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0xFF).toByte()
        file.writeBytes(bytes)

        assertUnavailable(SecretUnavailableException.Reason.CORRUPT) { store.loadSecret() }
        assertEquals(1, provider.createCount)
    }

    @Test
    fun missingFileThrowsTypedErrorWithoutCreatingKey() {
        val provider = FakeKeyProvider()
        val (store, _) = newStore(provider)
        assertUnavailable(SecretUnavailableException.Reason.KEY_MISSING) { store.loadSecret() }
        assertEquals(0, provider.createCount)
    }

    @Test
    fun clearSecretIsTheOnlyOperationThatDeletesTheKey() = runBlocking {
        val provider = FakeKeyProvider()
        val (store, file) = newStore(provider)
        store.storeSecret(ByteArray(32) { 1 }) {}
        assertEquals(0, provider.deleteCount)

        store.clearSecret()

        assertFalse(file.exists())
        assertEquals(1, provider.deleteCount)
        assertNull(provider.key)
    }

    @Test
    fun keystoreIoRunsOnInjectedDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "keystore-io-test") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val threads = mutableListOf<String>()
            val provider = object : WrappingKeyProvider {
                private var key: SecretKey? = null

                override fun getExistingKey(): SecretKey? {
                    threads += Thread.currentThread().name
                    return key
                }

                override fun peekKey(): SecretKey? = getExistingKey()

                override fun getOrCreateKey(): Pair<SecretKey, String> {
                    val existing = key ?: KeyGenerator.getInstance("AES").apply { init(256) }
                        .generateKey().also { key = it }
                    return Pair(existing, "Software")
                }

                override fun deleteKey() {
                    key = null
                }
            }
            val file = File(tmp.root, "dispatched.enc")
            val store = AndroidKeystoreSecretStore(provider, file, dispatcher)
            store.storeSecret(ByteArray(32) { 5 }) {}
            store.loadSecret()
            assertTrue(threads.isNotEmpty())
            assertTrue(
                "Keystore work must not run on the caller thread: $threads",
                threads.all { it.startsWith("keystore-io-test") }
            )
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun protectionLevelReadNeverCreatesAKey() = runBlocking {
        val provider = FakeKeyProvider()
        val (store, _) = newStore(provider)
        assertNull(store.getProtectionLevel())
        assertEquals(0, provider.createCount)
    }
}
