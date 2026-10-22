package com.nostrpost.app.storage

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-05: changing/removing a signer must not wipe the draft or relay list. */
class PreferencesIdentityClearTest {

    @Test
    fun clearingIdentityKeepsDraftAndRelays() {
        val prefs = mutablePreferencesOf(
            Preferences.KEY_DRAFT to "an unpublished thought",
            Preferences.KEY_RELAYS to """["wss://relay.example.com"]""",
            Preferences.KEY_PUBKEY to "ab".repeat(32),
            Preferences.KEY_NPUB to "npub1example",
            Preferences.KEY_SIGNER_MODE to "local",
            Preferences.KEY_SIGNER_PACKAGE to "com.example.signer",
            Preferences.KEY_IS_SETUP to true,
            Preferences.KEY_KEY_PROTECTION to "Hardware-backed"
        )

        clearIdentityKeys(prefs)

        assertEquals("an unpublished thought", prefs[Preferences.KEY_DRAFT])
        assertEquals("""["wss://relay.example.com"]""", prefs[Preferences.KEY_RELAYS])
        assertNull(prefs[Preferences.KEY_PUBKEY])
        assertNull(prefs[Preferences.KEY_NPUB])
        assertNull(prefs[Preferences.KEY_SIGNER_MODE])
        assertNull(prefs[Preferences.KEY_SIGNER_PACKAGE])
        assertNull(prefs[Preferences.KEY_IS_SETUP])
        assertNull(prefs[Preferences.KEY_KEY_PROTECTION])
    }

    @Test
    fun identityKeysNeverIncludeUserDataKeys() {
        val identity = identityPreferenceKeys()
        assertTrue(Preferences.KEY_DRAFT !in identity)
        assertTrue(Preferences.KEY_RELAYS !in identity)
    }
}
