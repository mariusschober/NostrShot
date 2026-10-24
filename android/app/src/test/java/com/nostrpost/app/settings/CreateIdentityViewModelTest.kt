package com.nostrpost.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.signing.IdentityGenerator
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-41: the generated identity survives recomposition and configuration changes. */
class CreateIdentityViewModelTest {

    private val pubkey = "ab".repeat(32)
    private val secret = ByteArray(32) { (it + 1).toByte() }

    private fun newViewModel(): CreateIdentityViewModel = CreateIdentityViewModel {
        IdentityGenerator.GeneratedIdentity(secret.copyOf(), pubkey)
    }

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
    fun secretIsClearedWhenViewModelIsCleared() {
        val viewModel = newViewModel()
        val secretReference = viewModel.secretBytes
        assertFalse(secretReference.all { it == 0.toByte() })

        clear(viewModel)

        assertTrue(secretReference.all { it == 0.toByte() })
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
}
