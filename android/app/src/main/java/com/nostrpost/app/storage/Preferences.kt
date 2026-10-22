package com.nostrpost.app.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nostr_post_prefs")

/**
 * Keys that belong to the *identity* only. Clearing the identity (change/remove signer) must
 * never touch the user's draft or relay list.
 */
internal fun identityPreferenceKeys(): Set<Preferences.Key<*>> = setOf(
    Preferences.KEY_PUBKEY,
    Preferences.KEY_NPUB,
    Preferences.KEY_SIGNER_MODE,
    Preferences.KEY_SIGNER_PACKAGE,
    Preferences.KEY_IS_SETUP,
    Preferences.KEY_KEY_PROTECTION
)

/** Removes only identity keys; draft and relays stay untouched. */
internal fun clearIdentityKeys(prefs: MutablePreferences) {
    identityPreferenceKeys().forEach { prefs.remove(it) }
}

class Preferences(private val context: Context) {

    companion object {
        val KEY_DRAFT = stringPreferencesKey("draft")
        val KEY_IS_SETUP = booleanPreferencesKey("is_setup")
        val KEY_SIGNER_MODE = stringPreferencesKey("signer_mode") // "nip55" | "local"
        val KEY_PUBKEY = stringPreferencesKey("pubkey_hex")
        val KEY_SIGNER_PACKAGE = stringPreferencesKey("signer_package")
        val KEY_RELAYS = stringPreferencesKey("relays_json") // JSON array string
        val KEY_KEY_PROTECTION = stringPreferencesKey("key_protection") // StrongBox, Hardware, Software
        val KEY_NPUB = stringPreferencesKey("npub_cache")
        val KEY_PENDING_SHARE = stringPreferencesKey("pending_share")
    }

    val draftFlow: Flow<String> = context.dataStore.data.map { it[KEY_DRAFT] ?: "" }
    val isSetupFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_IS_SETUP] ?: false }
    val signerModeFlow: Flow<String?> = context.dataStore.data.map { it[KEY_SIGNER_MODE] }
    val pubkeyFlow: Flow<String?> = context.dataStore.data.map { it[KEY_PUBKEY] }
    val signerPackageFlow: Flow<String?> = context.dataStore.data.map { it[KEY_SIGNER_PACKAGE] }
    val keyProtectionFlow: Flow<String?> = context.dataStore.data.map { it[KEY_KEY_PROTECTION] }
    val pendingShareFlow: Flow<String?> = context.dataStore.data.map { it[KEY_PENDING_SHARE] }

    val relaysFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_RELAYS]
        if (json.isNullOrBlank()) com.nostrpost.app.nostr.RelayConfig.DEFAULT_RELAYS
        else {
            try {
                val arr = org.json.JSONArray(json)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i))
                if (out.isEmpty()) com.nostrpost.app.nostr.RelayConfig.DEFAULT_RELAYS else out
            } catch (_: Exception) { com.nostrpost.app.nostr.RelayConfig.DEFAULT_RELAYS }
        }
    }

    suspend fun setDraft(value: String) {
        context.dataStore.edit { it[KEY_DRAFT] = value }
    }

    suspend fun setSetupComplete(v: Boolean) = context.dataStore.edit { it[KEY_IS_SETUP] = v }
    suspend fun setSignerMode(mode: String) = context.dataStore.edit { it[KEY_SIGNER_MODE] = mode }
    suspend fun setPubkey(hex: String) = context.dataStore.edit { it[KEY_PUBKEY] = hex; it[KEY_NPUB] = try { com.nostrpost.app.nostr.Bech32.toNpub(hex) } catch (_:Exception){ "" } }
    suspend fun setSignerPackage(pkg: String) = context.dataStore.edit { it[KEY_SIGNER_PACKAGE] = pkg }
    suspend fun setKeyProtection(level: String) = context.dataStore.edit { it[KEY_KEY_PROTECTION] = level }
    suspend fun setRelays(list: List<String>) {
        val json = org.json.JSONArray(list).toString()
        context.dataStore.edit { it[KEY_RELAYS] = json }
    }
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    /** Clear the stored identity while keeping the draft and relay configuration. */
    suspend fun clearIdentityOnly() {
        context.dataStore.edit { prefs -> clearIdentityKeys(prefs) }
    }
    suspend fun clearDraft() = context.dataStore.edit { it[KEY_DRAFT] = "" }

    suspend fun setPendingShare(text: String) {
        context.dataStore.edit { it[KEY_PENDING_SHARE] = text }
    }

    suspend fun clearPendingShare() {
        context.dataStore.edit { it.remove(KEY_PENDING_SHARE) }
    }

    // Synchronous quick read for widget? Use flow first; for simple we provide suspend functions
    suspend fun getDraftOnce(): String {
        return draftFlow.first()
    }
}
