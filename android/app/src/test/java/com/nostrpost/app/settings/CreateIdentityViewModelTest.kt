package com.nostrpost.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.signing.IdentityGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-41: the generated identity survives recomposition and configuration changes. */
class CreateIdentityViewModelTest {

    private val pubkey = "ab".repeat(32)
    private val secret = ByteArray(32) { (it + 1).toByte() }

    private fun newViewModel(): CreateIdentityViewModel = CreateIdentityViewModel {
        IdentityGenerator.GeneratedIdentity(secret.copyOf(), pubkey)
    }

    private fun newViewModel(
        identityFactory: () -> IdentityGenerator.GeneratedIdentity
    ): CreateIdentityViewModel = CreateIdentityViewModel(identityFactory)

    private fun clear(viewModel: CreateIdentityViewModel) {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
        }
        ViewModelProvider(store, factory)[CreateIdentityViewModel::class.java]
        store.clear()
    }

    @Test
    fun identityIsStableAcrossRepeatedAccess() {
        val viewModel = newViewModel()

        val firstNsec = viewModel.nsec()
        assertEquals(firstNsec, viewModel.nsec())
        assertEquals(Bech32.encode("nsec", secret), firstNsec)
        assertEquals(firstNsec.toCharArray().toList(), viewModel.nsecChars.toList())
        assertFalse(viewModel.nsecChars.all { it == '\u0000' })
        assertEquals(Bech32.toNpub(pubkey), viewModel.npub())
        assertEquals(pubkey, viewModel.pubkeyHex())
        assertEquals(0, viewModel.step.value)
    }

    @Test
    fun stepProgressesForwardAndBack() {
        val viewModel = newViewModel()

        viewModel.goBackToBackupStep()
        assertEquals(0, viewModel.step.value)

        viewModel.goToConfirmStep()
        assertEquals(1, viewModel.step.value)
        viewModel.goToConfirmStep()
        assertEquals(1, viewModel.step.value)

        viewModel.goBackToBackupStep()
        assertEquals(0, viewModel.step.value)
    }

    @Test
    fun prepareKeepsIdentityForConfigurationChange() {
        val viewModel = newViewModel()
        val secretReference = viewModel.secretBytes
        val nsecReference = viewModel.nsecChars
        val nsec = viewModel.nsec()

        viewModel.prepare()

        assertFalse(secretReference.all { it == 0.toByte() })
        assertFalse(nsecReference.all { it == '\u0000' })
        assertEquals(nsec, viewModel.nsec())
        assertEquals(nsec.toCharArray().toList(), viewModel.nsecChars.toList())
        assertEquals(Bech32.toNpub(pubkey), viewModel.npub())
    }

    @Test
    fun discardZeroesSecretAndNextVisitGeneratesFreshIdentity() {
        val freshSecret = ByteArray(32) { (it + 101).toByte() }
        val freshPubkey = "cd".repeat(32)
        var generations = 0
        val viewModel = newViewModel {
            generations++
            if (generations == 1) {
                IdentityGenerator.GeneratedIdentity(secret.copyOf(), pubkey)
            } else {
                IdentityGenerator.GeneratedIdentity(freshSecret.copyOf(), freshPubkey)
            }
        }
        val secretReference = viewModel.secretBytes
        val nsecReference = viewModel.nsecChars
        val oldNsec = viewModel.nsec()
        viewModel.goToConfirmStep()

        viewModel.discard()

        assertEquals(1, generations)
        assertTrue(secretReference.all { it == 0.toByte() })
        assertTrue(nsecReference.all { it == '\u0000' })
        assertTrue(viewModel.nsecChars.all { it == '\u0000' })
        assertTrue(viewModel.nsec().isEmpty())
        assertEquals(0, viewModel.step.value)

        viewModel.prepare()

        assertEquals(2, generations)
        assertArrayEquals(freshSecret, viewModel.secretBytes)
        assertEquals(Bech32.encode("nsec", freshSecret), viewModel.nsec())
        assertFalse(viewModel.nsecChars.all { it == '\u0000' })
        assertEquals(Bech32.toNpub(freshPubkey), viewModel.npub())
        assertEquals(0, viewModel.step.value)
        assertFalse(oldNsec == viewModel.nsec())
    }

    @Test
    fun secretIsClearedWhenViewModelIsCleared() {
        val viewModel = newViewModel()
        val secretReference = viewModel.secretBytes
        val nsecReference = viewModel.nsecChars
        assertFalse(secretReference.all { it == 0.toByte() })
        assertFalse(nsecReference.all { it == '\u0000' })

        clear(viewModel)

        assertTrue(secretReference.all { it == 0.toByte() })
        assertTrue(nsecReference.all { it == '\u0000' })
        assertTrue(viewModel.nsec().isEmpty())
    }

    @Test
    fun discardAndBackDiscardsBeforeNavigating() {
        val freshSecret = ByteArray(32) { (it + 101).toByte() }
        val freshPubkey = "cd".repeat(32)
        var generations = 0
        val viewModel = newViewModel {
            generations++
            if (generations == 1) {
                IdentityGenerator.GeneratedIdentity(secret.copyOf(), pubkey)
            } else {
                IdentityGenerator.GeneratedIdentity(freshSecret.copyOf(), freshPubkey)
            }
        }
        val secretReference = viewModel.secretBytes
        val nsecReference = viewModel.nsecChars
        var backs = 0

        viewModel.discardAndBack {
            backs++
            // The navigation callback must only run after the secret has been wiped.
            assertTrue(secretReference.all { it == 0.toByte() })
            assertTrue(nsecReference.all { it == '\u0000' })
            assertTrue(viewModel.nsec().isEmpty())
        }

        assertEquals(1, backs)
        assertEquals(0, viewModel.step.value)
        viewModel.prepare()
        assertEquals(2, generations)
        assertArrayEquals(freshSecret, viewModel.secretBytes)
        assertEquals(Bech32.encode("nsec", freshSecret), viewModel.nsec())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmStoresInViewModelScopeAndReportsSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = newViewModel()
            viewModel.goToConfirmStep()
            var storedSecret: ByteArray? = null
            var storedPubkey: String? = null

            viewModel.confirm { secretBytes, pubkeyHex ->
                storedSecret = secretBytes.copyOf()
                storedPubkey = pubkeyHex
            }
            assertTrue(viewModel.saving.value)

            advanceUntilIdle()

            assertArrayEquals(secret, storedSecret)
            assertEquals(pubkey, storedPubkey)
            assertEquals(CreateIdentityEffect.Stored, viewModel.effect.value)
            assertFalse(viewModel.saving.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun discardDuringSaveDoesNotChangeBytesSeenByStore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = newViewModel()
            viewModel.goToConfirmStep()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var received: ByteArray? = null
            var receivedPubkey: String? = null

            viewModel.confirm { secretBytes, pubkeyHex ->
                received = secretBytes.copyOf()
                receivedPubkey = pubkeyHex
                entered.complete(Unit)
                release.await()
            }
            advanceUntilIdle()
            assertTrue(entered.isCompleted)

            viewModel.discard()

            assertArrayEquals(secret, received)
            assertEquals(pubkey, receivedPubkey)
            assertFalse(
                "discard must not zero the identity while the save is in flight",
                viewModel.secretBytes.all { it == 0.toByte() }
            )

            release.complete(Unit)
            advanceUntilIdle()

            assertArrayEquals(secret, received)
            assertEquals(pubkey, receivedPubkey)
            assertEquals(CreateIdentityEffect.Stored, viewModel.effect.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onClearedDuringSaveDoesNotCorruptBytesAndSaveCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = newViewModel()
            viewModel.goToConfirmStep()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var received: ByteArray? = null
            var storeReference: ByteArray? = null

            viewModel.confirm { secretBytes, _ ->
                storeReference = secretBytes
                received = secretBytes.copyOf()
                entered.complete(Unit)
                release.await()
            }
            advanceUntilIdle()
            assertTrue(entered.isCompleted)

            clear(viewModel)

            assertFalse("clearing must not abort a started save", release.isCompleted)
            release.complete(Unit)
            advanceUntilIdle()

            assertArrayEquals(secret, received)
            assertNotNull(storeReference)
            assertTrue(
                "the store's copy must be wiped by the save",
                storeReference!!.all { it == 0.toByte() }
            )
            assertTrue(
                "onCleared only wipes the ViewModel's own array",
                viewModel.secretBytes.all { it == 0.toByte() }
            )
            assertEquals(null, viewModel.effect.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun storeCopyIsZeroedAfterSuccessfulSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = newViewModel()
            viewModel.goToConfirmStep()
            var storeReference: ByteArray? = null
            var received: ByteArray? = null

            viewModel.confirm { secretBytes, pubkeyHex ->
                storeReference = secretBytes
                received = secretBytes.copyOf()
                assertEquals(pubkey, pubkeyHex)
            }
            advanceUntilIdle()

            assertArrayEquals(secret, received)
            assertNotNull(storeReference)
            assertTrue(storeReference!!.all { it == 0.toByte() })
            assertEquals(CreateIdentityEffect.Stored, viewModel.effect.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun storeCopyIsZeroedAfterFailedSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = newViewModel()
            viewModel.goToConfirmStep()
            var storeReference: ByteArray? = null
            var received: ByteArray? = null

            viewModel.confirm { secretBytes, _ ->
                storeReference = secretBytes
                received = secretBytes.copyOf()
                throw IllegalStateException("save failed")
            }
            advanceUntilIdle()

            assertArrayEquals(secret, received)
            assertNotNull(storeReference)
            assertTrue(storeReference!!.all { it == 0.toByte() })
            assertEquals(CreateIdentityEffect.Failed("save failed"), viewModel.effect.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun discardAndBackDuringSaveIsIgnoredAndDiscardsAfterSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = newViewModel()
            viewModel.goToConfirmStep()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var received: ByteArray? = null
            var backs = 0

            viewModel.confirm { secretBytes, _ ->
                received = secretBytes.copyOf()
                entered.complete(Unit)
                release.await()
            }
            advanceUntilIdle()
            assertTrue(entered.isCompleted)

            viewModel.discardAndBack { backs++ }

            assertEquals("back must be ignored while saving", 0, backs)
            assertTrue(viewModel.saving.value)
            assertFalse(viewModel.secretBytes.all { it == 0.toByte() })
            assertArrayEquals(secret, received)

            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, backs)
            assertTrue(viewModel.secretBytes.all { it == 0.toByte() })
            assertTrue(viewModel.nsec().isEmpty())
            assertEquals(0, viewModel.step.value)
            assertEquals(CreateIdentityEffect.Stored, viewModel.effect.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun allZeroSecretIsRefused() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = newViewModel {
                IdentityGenerator.GeneratedIdentity(ByteArray(32), pubkey)
            }
            viewModel.goToConfirmStep()
            var storeCalled = false

            viewModel.confirm { _, _ -> storeCalled = true }
            advanceUntilIdle()

            assertFalse("an all-zero private key must never reach the store", storeCalled)
            val effect = viewModel.effect.value
            assertTrue(effect is CreateIdentityEffect.Failed)
            assertTrue(
                (effect as CreateIdentityEffect.Failed).message
                    .contains("all-zero", ignoreCase = true)
            )
            assertFalse(viewModel.saving.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
