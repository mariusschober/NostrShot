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

    suspend fun storeLocalIdentity(privHex: String, pubkeyHex: String, protection: String) {
        // priv already validated
        val privBytes = Hex.decode(privHex)
        try {
            secretStore.storeSecret(privBytes) { lvl ->
                // will be saved via prefs below
            }
        } finally {
            privBytes.fill(0)
        }
        prefs.setPubkey(pubkeyHex.lowercase())
        prefs.setSignerMode("local")
        prefs.setKeyProtection(protection)
        prefs.setSetupComplete(true)
        // Also cache signer package empty
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
                if (!EventVerifier.verify(signed, expectedContent = content, expectedPubkey = pubkey)) {
                    return SignOutcome.Error("Verification failed after local sign")
                }
                SignOutcome.Signed(signed)
            } finally {
                privBytes.fill(0)
            }
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
                    if (!EventVerifier.verify(signed, expectedContent = content, expectedPubkey = pubkey)) {
                        return SignOutcome.Error("External signer returned invalid event")
                    }
                    // Also verify id matches our computed? Use signed's id
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

    /**
     * Build intent for interactive signing (to be launched via registerForActivityResult).
     */
    suspend fun buildSignIntent(content: String): android.content.Intent? {
        val pubkey = getStoredPubkey() ?: return null
        val pkg = getStoredSignerPackage() ?: return null
        if (!isPackageInstalled(pkg)) return null
        val unsigned = EventFactory.createUnsigned(pubkey, content)
        val eventJson = EventFactory.unsignedToSignerJson(unsigned)
        // Use id as extra for correlation (optional)
        val id = EventFactory.computeId(EventFactory.canonicalJson(unsigned))
        return Nip55.createSignEventIntent(eventJson, pubkey, pkg, id)
    }

    /**
     * Parse result from sign_event Intent.
     */
    fun parseSignResult(data: android.content.Intent?, expectedContent: String, expectedPubkey: String): SignOutcome {
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
            if (!EventVerifier.verify(signed, expectedContent = expectedContent, expectedPubkey = expectedPubkey)) {
                return SignOutcome.Error("Signer verification failed")
            }
            // sig should match signed.sig
            if (!signed.sig.equals(sig, ignoreCase = true)) {
                Log.w("SignerRepo", "Signature mismatch between result and event.sig")
                // Still accept if event verifies
            }
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

    suspend fun clearIdentity() {
        prefs.clearAll()
        try { secretStore.clearSecret() } catch (_: Exception) {}
    }
}
