package com.nostrpost.app.signing

import com.nostrpost.app.nostr.EventVerifier
import com.nostrpost.app.nostr.SignedEvent
import com.nostrpost.app.nostr.UnsignedEvent

/** Validates an external signer's response against the exact unsigned event we sent. */
internal object SignerVerification {

    /**
     * Returns null when the response is safe to publish, or a user-facing error message.
     * The signature returned in the response must also match the signature inside the event.
     */
    fun errorFor(
        signed: SignedEvent,
        expected: UnsignedEvent,
        returnedSignature: String?
    ): String? {
        if (!signed.pubkey.equals(expected.pubkey, ignoreCase = true)) {
            return "Signer returned an event for a different pubkey"
        }
        if (signed.createdAt != expected.createdAt) {
            return "Signer changed the event timestamp"
        }
        if (signed.kind != expected.kind) {
            return "Signer changed the event kind"
        }
        if (signed.tags != expected.tags) {
            return "Signer added or changed tags"
        }
        if (signed.content != expected.content) {
            return "Signer changed the note content"
        }
        if (!returnedSignature.isNullOrBlank() &&
            !signed.sig.equals(returnedSignature, ignoreCase = true)
        ) {
            return "Signer returned mismatched signatures"
        }
        if (!EventVerifier.verify(signed, expected)) {
            return "Signer returned an invalid event"
        }
        return null
    }
}
