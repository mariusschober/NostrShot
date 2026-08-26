package com.nostrpost.app.composer

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostrpost.app.nostr.RelayPublisher
import com.nostrpost.app.signing.SignerRepository
import com.nostrpost.app.storage.Preferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ComposerState(
    val content: String = "",
    val isPublishing: Boolean = false,
    val showSuccess: Boolean = false,
    val errorMessage: String? = null,
    val isSetup: Boolean = false,
    val pubkey: String? = null,
    val relays: List<String> = emptyList()
)

sealed class ComposerEffect {
    object CloseApp : ComposerEffect()
    data class LaunchSignerIntent(val intent: Intent, val contentSnapshot: String) : ComposerEffect()
    data class ShowMessage(val msg: String) : ComposerEffect()
}

class ComposerViewModel(
    private val prefs: Preferences,
    private val signerRepo: SignerRepository,
    private val relayPublisher: RelayPublisher
) : ViewModel() {

    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state

    private val _effect = MutableStateFlow<ComposerEffect?>(null)
    val effect: StateFlow<ComposerEffect?> = _effect

    private var draftJob: Job? = null
    private var pendingPublishContent: String? = null

    init {
        viewModelScope.launch {
            // Restore draft and setup info
            val draft = prefs.draftFlow.first()
            val isSetup = prefs.isSetupFlow.first()
            val pubkey = prefs.pubkeyFlow.first()
            val relays = prefs.relaysFlow.first()
            _state.value = _state.value.copy(content = draft, isSetup = isSetup, pubkey = pubkey, relays = relays)
            // Observe setup changes
            launch {
                prefs.isSetupFlow.collect { isS -> _state.value = _state.value.copy(isSetup = isS) }
            }
            launch {
                prefs.pubkeyFlow.collect { pk -> _state.value = _state.value.copy(pubkey = pk) }
            }
            launch {
                prefs.relaysFlow.collect { r -> _state.value = _state.value.copy(relays = r) }
            }
        }
    }

    fun onContentChange(new: String) {
        _state.value = _state.value.copy(content = new, errorMessage = null)
        // Debounce draft save 300ms
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(300)
            prefs.setDraft(new)
        }
        // immediate save on clear? We'll also save on pause via activity
    }

    fun clearEffect() { _effect.value = null }

    fun onPublishClicked() {
        val content = _state.value.content.trim()
        android.util.Log.i("ComposerVM", "Publish clicked: contentLen=${content.length} isSetup=${_state.value.isSetup}")
        if (content.isEmpty()) return
        if (_state.value.isPublishing) return
        viewModelScope.launch {
            android.util.Log.i("ComposerVM", "Starting publish len=${content.length}")
            _state.value = _state.value.copy(isPublishing = true, errorMessage = null)
            // Decide signer mode
            val mode = signerRepo.getStoredSignerMode()
            when (mode) {
                "nip55" -> {
                    // Try background first
                    val bgResult = signerRepo.tryBackgroundSign(content)
                    when {
                        bgResult == null -> {
                            // Need interactive intent
                            _state.value = _state.value.copy(isPublishing = false)
                            val intent = signerRepo.buildSignIntent(content)
                            if (intent == null) {
                                _state.value = _state.value.copy(errorMessage = "Signer unavailable. Choose another signing method in Settings.")
                                pendingPublishContent = null
                            } else {
                                pendingPublishContent = content
                                _effect.value = ComposerEffect.LaunchSignerIntent(intent, content)
                            }
                        }
                        bgResult is SignerRepository.SignOutcome.Signed -> {
                            handlePublishRelay(bgResult.event)
                        }
                        bgResult is SignerRepository.SignOutcome.Cancelled -> {
                            _state.value = _state.value.copy(isPublishing = false, errorMessage = "Signing cancelled. If Amber still asks PIN, go to Settings → Test one-tap → Fix.")
                        }
                        bgResult is SignerRepository.SignOutcome.SignerUnavailable -> {
                            _state.value = _state.value.copy(isPublishing = false, errorMessage = "Signer unavailable. Reinstall or change signer.")
                        }
                        bgResult is SignerRepository.SignOutcome.Error -> {
                            _state.value = _state.value.copy(isPublishing = false, errorMessage = bgResult.message + " — Settings → Test one-tap")
                        }
                    }
                }
                "local" -> {
                    val result = signerRepo.signLocally(content)
                    when (result) {
                        is SignerRepository.SignOutcome.Signed -> handlePublishRelay(result.event)
                        is SignerRepository.SignOutcome.Error -> _state.value = _state.value.copy(isPublishing = false, errorMessage = result.message)
                        else -> _state.value = _state.value.copy(isPublishing = false, errorMessage = "Unable to sign.")
                    }
                }
                else -> {
                    _state.value = _state.value.copy(isPublishing = false, errorMessage = "Not set up. Go to Settings.")
                }
            }
        }
    }

    private suspend fun handlePublishRelay(event: com.nostrpost.app.nostr.SignedEvent) {
        // publish
        android.util.Log.i("ComposerVM", "handlePublishRelay id=${event.id} pub=${event.pubkey.take(8)}")
        val relays = prefs.relaysFlow.first()
        android.util.Log.i("ComposerVM", "Publishing to relays: $relays")
        val result = relayPublisher.publish(event, relays)
        if (result.success) {
            // clear draft
            prefs.clearDraft()
            _state.value = _state.value.copy(content = "", isPublishing = false, showSuccess = true)
            delay(420)
            _effect.value = ComposerEffect.CloseApp
        } else {
            _state.value = _state.value.copy(isPublishing = false, errorMessage = result.error ?: "Couldn't publish. Your post is still here.")
            // draft preserved (we didn't clear)
            // keep content
        }
    }

    fun onSignerActivityResult(data: Intent?, resultCode: Int, expectedContent: String) {
        viewModelScope.launch {
            if (resultCode != android.app.Activity.RESULT_OK) {
                _state.value = _state.value.copy(isPublishing = false, errorMessage = "Signing cancelled.")
                return@launch
            }
            val expectedPubkey = signerRepo.getStoredPubkey() ?: ""
            val outcome = signerRepo.parseSignResult(data, expectedContent, expectedPubkey)
            when (outcome) {
                is SignerRepository.SignOutcome.Signed -> {
                    _state.value = _state.value.copy(isPublishing = true)
                    handlePublishRelay(outcome.event)
                }
                is SignerRepository.SignOutcome.Cancelled -> {
                    _state.value = _state.value.copy(isPublishing = false, errorMessage = "Signing cancelled.")
                }
                is SignerRepository.SignOutcome.Error -> {
                    _state.value = _state.value.copy(isPublishing = false, errorMessage = outcome.message)
                }
                else -> _state.value = _state.value.copy(isPublishing = false, errorMessage = "Signing failed")
            }
        }
    }

    fun onRetry() {
        _state.value = _state.value.copy(errorMessage = null)
        onPublishClicked()
    }

    suspend fun saveDraftImmediately() {
        prefs.setDraft(_state.value.content)
    }

    fun dismissError() { _state.value = _state.value.copy(errorMessage = null) }
}
