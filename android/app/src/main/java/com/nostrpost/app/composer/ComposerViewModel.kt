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

enum class ComposerErrorAction {
    /** Transient publish failure: show Retry. */
    RETRY,

    /** Signing/configuration problem: send the user to Settings. */
    OPEN_SETTINGS,

    /** Terminal/self-explanatory message: only allow dismissing. */
    DISMISS
}

data class ComposerState(
    val content: String = "",
    val isPublishing: Boolean = false,
    val showSuccess: Boolean = false,
    val errorMessage: String? = null,
    val errorAction: ComposerErrorAction = ComposerErrorAction.DISMISS,
    val isSetup: Boolean = false,
    val pubkey: String? = null,
    val relays: List<String> = emptyList()
)

sealed class ComposerEffect {
    object CloseApp : ComposerEffect()
    data class LaunchSignerIntent(
        val intent: Intent,
        val unsigned: com.nostrpost.app.nostr.UnsignedEvent
    ) : ComposerEffect()
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
    private var consumedPendingShare = false

    init {
        viewModelScope.launch {
            // Restore draft and setup info
            val draft = prefs.draftFlow.first()
            val isSetup = prefs.isSetupFlow.first()
            val pubkey = prefs.pubkeyFlow.first()
            val relays = prefs.relaysFlow.first()
            _state.value = _state.value.copy(content = draft, isSetup = isSetup, pubkey = pubkey, relays = relays)
            // Observe setup changes; a share that arrived before onboarding is consumed once setup
            // completes instead of being lost or overwriting later typing.
            launch {
                prefs.isSetupFlow.collect { isS ->
                    _state.value = _state.value.copy(isSetup = isS)
                    if (isS) consumePendingShareIfAny()
                }
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

    /**
     * Applies text shared into the app. Before onboarding the text is queued and applied once
     * setup completes; afterwards it is merged into the current draft (never silently replaces
     * what the user already typed).
     */
    fun onSharedText(text: String) {
        val shared = text.trim()
        if (shared.isEmpty()) return
        if (_state.value.isSetup) {
            applySharedText(shared)
            viewModelScope.launch { prefs.clearPendingShare() }
        } else {
            viewModelScope.launch {
                val pending = prefs.pendingShareFlow.first().orEmpty()
                prefs.setPendingShare(mergeSharedText(pending, shared))
            }
        }
    }

    private suspend fun consumePendingShareIfAny() {
        if (consumedPendingShare) return
        consumedPendingShare = true
        val pending = prefs.pendingShareFlow.first()?.trim().orEmpty()
        if (pending.isEmpty()) return
        applySharedText(pending)
        prefs.clearPendingShare()
    }

    private fun applySharedText(shared: String) {
        val merged = mergeSharedText(_state.value.content, shared)
        _state.value = _state.value.copy(content = merged, errorMessage = null)
        draftJob?.cancel()
        viewModelScope.launch { prefs.setDraft(merged) }
    }

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
                            val request = signerRepo.buildSignIntent(content)
                            if (request == null) {
                                _state.value = _state.value.copy(
                                    errorMessage = "Signer unavailable. Choose another signing method in Settings.",
                                    errorAction = ComposerErrorAction.OPEN_SETTINGS
                                )
                                pendingPublishContent = null
                            } else {
                                pendingPublishContent = content
                                _effect.value = ComposerEffect.LaunchSignerIntent(request.intent, request.unsigned)
                            }
                        }
                        bgResult is SignerRepository.SignOutcome.Signed -> {
                            handlePublishRelay(bgResult.event)
                        }
                        bgResult is SignerRepository.SignOutcome.Cancelled -> {
                            _state.value = _state.value.copy(
                                isPublishing = false,
                                errorMessage = "Signing cancelled. If your signer still asks for a PIN, open Settings → Test one-tap → Fix.",
                                errorAction = ComposerErrorAction.OPEN_SETTINGS
                            )
                        }
                        bgResult is SignerRepository.SignOutcome.SignerUnavailable -> {
                            _state.value = _state.value.copy(
                                isPublishing = false,
                                errorMessage = "Signer unavailable. Reinstall it or change signer in Settings.",
                                errorAction = ComposerErrorAction.OPEN_SETTINGS
                            )
                        }
                        bgResult is SignerRepository.SignOutcome.Error -> {
                            _state.value = _state.value.copy(
                                isPublishing = false,
                                errorMessage = bgResult.message + " — open Settings → Test one-tap.",
                                errorAction = ComposerErrorAction.OPEN_SETTINGS
                            )
                        }
                    }
                }
                "local" -> {
                    val result = signerRepo.signLocally(content)
                    when (result) {
                        is SignerRepository.SignOutcome.Signed -> handlePublishRelay(result.event)
                        is SignerRepository.SignOutcome.Error -> _state.value = _state.value.copy(
                            isPublishing = false,
                            errorMessage = result.message,
                            errorAction = ComposerErrorAction.OPEN_SETTINGS
                        )
                        else -> _state.value = _state.value.copy(
                            isPublishing = false,
                            errorMessage = "Unable to sign.",
                            errorAction = ComposerErrorAction.OPEN_SETTINGS
                        )
                    }
                }
                else -> {
                    _state.value = _state.value.copy(
                        isPublishing = false,
                        errorMessage = "Not set up. Open Settings to choose a signer.",
                        errorAction = ComposerErrorAction.OPEN_SETTINGS
                    )
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
            _state.value = _state.value.copy(
                isPublishing = false,
                errorMessage = result.error ?: "Couldn't publish. Your post is still here.",
                errorAction = ComposerErrorAction.RETRY
            )
            // draft preserved (we didn't clear)
            // keep content
        }
    }

    fun onSignerActivityResult(
        data: Intent?,
        resultCode: Int,
        expected: com.nostrpost.app.nostr.UnsignedEvent
    ) {
        viewModelScope.launch {
            if (resultCode != android.app.Activity.RESULT_OK) {
                _state.value = _state.value.copy(
                    isPublishing = false,
                    errorMessage = "Signing cancelled.",
                    errorAction = ComposerErrorAction.DISMISS
                )
                return@launch
            }
            val outcome = signerRepo.parseSignResult(data, expected)
            when (outcome) {
                is SignerRepository.SignOutcome.Signed -> {
                    _state.value = _state.value.copy(isPublishing = true)
                    handlePublishRelay(outcome.event)
                }
                is SignerRepository.SignOutcome.Cancelled -> {
                    _state.value = _state.value.copy(
                    isPublishing = false,
                    errorMessage = "Signing cancelled.",
                    errorAction = ComposerErrorAction.DISMISS
                )
                }
                is SignerRepository.SignOutcome.Error -> {
                    _state.value = _state.value.copy(
                        isPublishing = false,
                        errorMessage = outcome.message,
                        errorAction = ComposerErrorAction.OPEN_SETTINGS
                    )
                }
                else -> _state.value = _state.value.copy(
                    isPublishing = false,
                    errorMessage = "Signing failed",
                    errorAction = ComposerErrorAction.OPEN_SETTINGS
                )
            }
        }
    }

    fun onRetry() {
        _state.value = _state.value.copy(errorMessage = null, errorAction = ComposerErrorAction.DISMISS)
        onPublishClicked()
    }

    suspend fun saveDraftImmediately() {
        prefs.setDraft(_state.value.content)
    }

    fun dismissError() { _state.value = _state.value.copy(errorMessage = null) }
}

/** Merge shared text without truncating it or destroying an existing draft. */
internal fun mergeSharedText(current: String, shared: String): String {
    val trimmedShared = shared.trim()
    if (trimmedShared.isEmpty()) return current
    return if (current.isBlank()) trimmedShared else current.trimEnd() + "\n\n" + trimmedShared
}
