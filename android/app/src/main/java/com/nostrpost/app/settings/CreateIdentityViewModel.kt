package com.nostrpost.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.signing.IdentityGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CreateIdentityEffect {
    data object Stored : CreateIdentityEffect
    data class Failed(val message: String) : CreateIdentityEffect
}

/**
 * Holds the freshly generated identity for the lifetime of the activity's ViewModel store, so a
 * configuration change does not generate a new key or send the user back to step 0.
 *
 * The secret is deliberately kept out of saved instance state. It is zeroed when the ViewModel is
 * cleared, and when the screen explicitly discards the session (back/cancel or after a save).
 *
 * The nsec lives in [nsecBuffer] as a CharArray rather than a long-lived String so it can be
 * wiped. Compose's Text still needs a String, so [nsec] necessarily creates an immutable copy for
 * the UI. That copy cannot be zeroed and may remain reachable until the composition/framework
 * releases it and the GC reclaims it; callers should request it only while it is displayed.
 */
class CreateIdentityViewModel(
    private val identityFactory: () -> IdentityGenerator.GeneratedIdentity
) : ViewModel() {

    constructor() : this({ IdentityGenerator.generate() })

    private var identity = identityFactory()
    private var nsecBuffer: CharArray = encodeNsec(identity.secret)
    private var npubValue = try {
        Bech32.toNpub(identity.pubkeyHex)
    } catch (_: Exception) {
        identity.pubkeyHex
    }
    private var needsNewIdentity = false
    private var secretAvailable = true

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _effect = MutableStateFlow<CreateIdentityEffect?>(null)
    val effect: StateFlow<CreateIdentityEffect?> = _effect.asStateFlow()

    internal val secretBytes: ByteArray
        get() = identity.secret

    /** Test-visible view of the wipeable nsec buffer. Do not keep this reference in UI code. */
    internal val nsecChars: CharArray
        get() = nsecBuffer

    /**
     * Returns a fresh String copy only while the identity is live. The copy is transient and
     * cannot be wiped, because Compose Text requires a String; [nsecBuffer] remains canonical.
     */
    fun nsec(): String = if (secretAvailable) String(nsecBuffer) else ""

    fun npub(): String = npubValue

    fun pubkeyHex(): String = identity.pubkeyHex

    fun goToConfirmStep() {
        if (_step.value == 0) _step.value = 1
    }

    fun goBackToBackupStep() {
        if (_step.value == 1 && !_saving.value) _step.value = 0
    }

    /**
     * Stores the identity in [viewModelScope] so a composition disposal (for example during a
     * rotation) cannot cancel the background save halfway through.
     */
    fun confirm(store: suspend (secret: ByteArray, pubkeyHex: String) -> Unit) {
        if (_saving.value || _step.value != 1) return
        _saving.value = true
        viewModelScope.launch {
            try {
                store(identity.secret, identity.pubkeyHex)
                _effect.value = CreateIdentityEffect.Stored
            } catch (e: Exception) {
                _effect.value = CreateIdentityEffect.Failed(e.message ?: "Could not create identity")
            } finally {
                _saving.value = false
            }
        }
    }

    /** Called after the screen has handled an effect. */
    fun clearEffect() {
        _effect.value = null
    }

    /** Re-creates an identity if the previous screen session was abandoned or completed. */
    fun prepare() {
        if (!needsNewIdentity) return
        identity.secret.fill(0)
        nsecBuffer.fill('\u0000')
        identity = identityFactory()
        nsecBuffer = encodeNsec(identity.secret)
        npubValue = try {
            Bech32.toNpub(identity.pubkeyHex)
        } catch (_: Exception) {
            identity.pubkeyHex
        }
        _step.value = 0
        _effect.value = null
        needsNewIdentity = false
        secretAvailable = true
    }

    /** Drops the current secret so the next visit starts with a fresh identity. */
    fun discard() {
        identity.secret.fill(0)
        nsecBuffer.fill('\u0000')
        npubValue = ""
        _step.value = 0
        _effect.value = null
        needsNewIdentity = true
        secretAvailable = false
    }

    /** Handles the system back gesture exactly like the explicit cancel button. */
    internal fun discardAndBack(onBack: () -> Unit) {
        discard()
        onBack()
    }

    override fun onCleared() {
        identity.secret.fill(0)
        nsecBuffer.fill('\u0000')
        secretAvailable = false
    }

    private fun encodeNsec(secret: ByteArray): CharArray =
        Bech32.encode("nsec", secret).toCharArray()
}
