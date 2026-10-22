package com.nostrpost.app.signing

import android.content.Context
import android.util.Log
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.nostr.EventFactory
import com.nostrpost.app.nostr.EventVerifier
import com.nostrpost.app.nostr.Hex
import com.nostrpost.app.nostr.NostrCrypto
import com.nostrpost.app.nostr.SignedEvent
import com.nostrpost.app.nostr.UnsignedEvent
import com.nostrpost.app.security.AndroidKeystoreSecretStore
import com.nostrpost.app.storage.Preferences
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Unified signer abstraction. Two modes: nip55 and local.
 */
class SignerRepository(
    private val context: Context,
    private val prefs: Preferences,
    private val secretStore: AndroidKeystoreSecretStore
) {

    sealed class SignOutcome {
        data class Signed(val event: SignedEvent) : SignOutcome()
        object Cancelled : SignOutcome()
        object SignerUnavailable : SignOutcome()
        data class Error(val message: String) : SignOutcome()
    }

    suspend fun getStoredPubkey(): String? = prefs.pubkeyFlow.first()
    suspend fun getStoredSignerMode(): String? = prefs.signerModeFlow.first()
    suspend fun getStoredSignerPackage(): String? = prefs.signerPackageFlow.first()

    fun isNip55Available(): Boolean = Nip55.isSignerAvailable(context)

    /**
     * Called after get_public_key Intent returns. Store pubkey + package.
     */
    suspend fun storeNip55Identity(pubkeyHex: String, packageName: String) {
        prefs.setPubkey(pubkeyHex.lowercase())
        prefs.setSignerPackage(packageName)
        prefs.setSignerMode("nip55")
        prefs.setSetupComplete(true)
    }

    suspend fun storeLocalIdentity(privHex: String, pubkeyHex: String) {
        // priv already validated
        val privBytes = Hex.decode(privHex)
        var protection = "Unknown"
        try {
            // The real protection level is only knowable after the wrapping key exists.
            secretStore.storeSecret(privBytes) { lvl -> protection = lvl }
        } finally {
            privBytes.fill(0)
        }
        prefs.setPubkey(pubkeyHex.lowercase())
        prefs.setSignerMode("local")
        prefs.setKeyProtection(protection)
        prefs.setSetupComplete(true)
    }

    /**
     * Sign via local store (decrypt, sign, clear).
     */
    suspend fun signLocally(content: String): SignOutcome {
        return try {
            val pubkey = getStoredPubkey() ?: return SignOutcome.Error("No pubkey")
            val unsigned = EventFactory.createUnsigned(pubkey, content)
            val canonical = EventFactory.canonicalJson(unsigned)
            val id = EventFactory.computeId(canonical)
            val idBytes = Hex.decode(id)
            val privBytes = secretStore.loadSecret() // 32 bytes
            try {
                val sig = NostrCrypto.sign(idBytes, privBytes)
                val signed = EventFactory.createSigned(unsigned, sig)
                // verify before return
                if (!EventVerifier.verify(signed, unsigned)) {
                    return SignOutcome.Error("Verification failed after local sign")
                }
                SignOutcome.Signed(signed)
            } finally {
                privBytes.fill(0)
            }
        } catch (e: com.nostrpost.app.security.SecretUnavailableException) {
            Log.w("SignerRepo", "Local key unavailable: ${e.reason}")
            SignOutcome.Error(
                e.message ?: com.nostrpost.app.security.AndroidKeystoreSecretStore.REIMPORT_MESSAGE
            )
        } catch (e: Exception) {
            Log.e("SignerRepo", "Local sign error", e)
            SignOutcome.Error(e.message ?: "Local signing failed")
        }
    }

    /**
     * Attempt background ContentResolver signing. Null means fallback to Intent needed.
     */
    suspend fun tryBackgroundSign(content: String): SignOutcome? {
        val mode = getStoredSignerMode()
        if (mode != "nip55") return null
        val pubkey = getStoredPubkey() ?: return SignOutcome.Error("No pubkey")
        val pkg = getStoredSignerPackage() ?: return SignOutcome.Error("No signer package")
        // check package still installed
        if (!isPackageInstalled(pkg)) return SignOutcome.SignerUnavailable

        val unsigned = EventFactory.createUnsigned(pubkey, content)
        val eventJsonForSigner = EventFactory.unsignedToSignerJson(unsigned)
        val result = Nip55.tryContentResolverSign(context, eventJsonForSigner, pkg, pubkey)
        return when (result) {
            is SignResult.Success -> {
                try {
                    val signed = EventFactory.signedEventFromJson(result.signedEventJson)
                    val error = SignerVerification.errorFor(signed, unsigned, result.signature)
                    if (error != null) return SignOutcome.Error(error)
                    SignOutcome.Signed(signed)
                } catch (e: Exception) {
                    SignOutcome.Error("Malformed signed event from signer: ${e.message}")
                }
            }
            is SignResult.Rejected -> SignOutcome.Cancelled
            is SignResult.Error -> SignOutcome.Error(result.message)
            null -> null // need Intent
        }
    }

    fun isPackageInstalled(pkg: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) { false }
    }

    /** The exact unsigned event plus the Intent that asks the signer to sign it. */
    data class SignRequest(val intent: android.content.Intent, val unsigned: UnsignedEvent)

    /**
     * Build intent for interactive signing (to be launched via registerForActivityResult).
     * The caller keeps [SignRequest.unsigned] and verifies the response against it.
     */
    suspend fun buildSignIntent(content: String): SignRequest? {
        val pubkey = getStoredPubkey() ?: return null
        val pkg = getStoredSignerPackage() ?: return null
        if (!isPackageInstalled(pkg)) return null
        val unsigned = EventFactory.createUnsigned(pubkey, content)
        val eventJson = EventFactory.unsignedToSignerJson(unsigned)
        // Use id as extra for correlation (optional)
        val id = EventFactory.computeId(EventFactory.canonicalJson(unsigned))
        val intent = Nip55.createSignEventIntent(eventJson, pubkey, pkg, id)
        return SignRequest(intent, unsigned)
    }

    /**
     * Parse result from sign_event Intent and require it to match [expected] exactly.
     */
    fun parseSignResult(data: android.content.Intent?, expected: UnsignedEvent): SignOutcome {
        if (data == null) return SignOutcome.Error("No data from signer")
        val rejected = data.getStringExtra("rejected") ?: data.getStringExtra("rejected".lowercase())
        // Some implementations use boolean extra?
        val rejectedBool = data.getBooleanExtra("rejected", false)
        if (rejectedBool || rejected == "true") return SignOutcome.Cancelled
        // Also check resultCode handled by caller (RESULT_OK expected)
        val sig = data.getStringExtra("result")
        val eventJson = data.getStringExtra("event")
        if (sig.isNullOrBlank() || eventJson.isNullOrBlank()) {
            // Check extras via data.getExtras()
            return SignOutcome.Error("Signer returned empty signature/event")
        }
        return try {
            val signed = EventFactory.signedEventFromJson(eventJson)
            val error = SignerVerification.errorFor(signed, expected, sig)
            if (error != null) return SignOutcome.Error(error)
            SignOutcome.Signed(signed)
        } catch (e: Exception) {
            SignOutcome.Error("Failed to parse signed event: ${e.message}")
        }
    }

    fun parseGetPublicKeyResult(data: android.content.Intent?): Pair<String, String>? {
        if (data == null) return null
        val pubkey = data.getStringExtra("result") // spec says result
        // Some signers return pubkey via "result" extra, package via "package"
        val pkg = data.getStringExtra("package")
        if (pubkey.isNullOrBlank() || pkg.isNullOrBlank()) {
            // Try alternative keys? log extras
            Log.w("SignerRepo", "get_public_key missing fields: pubkey=$pubkey pkg=$pkg extras=${data.extras?.keySet()}")
            return null
        }
        // Validate hex length
        if (pubkey.length != 64 || !pubkey.matches(Regex("[0-9a-fA-F]{64}"))) {
            Log.w("SignerRepo", "Invalid pubkey from signer: $pubkey")
            return null
        }
        return Pair(pubkey.lowercase(), pkg)
    }

    /**
     * Remove the current identity. The draft and relay list are intentionally preserved:
     * switching signers must not discard an unpublished note or a curated relay set.
     */
    suspend fun clearIdentity() {
        prefs.clearIdentityOnly()
        try { secretStore.clearSecret() } catch (_: Exception) {}
    }
}
