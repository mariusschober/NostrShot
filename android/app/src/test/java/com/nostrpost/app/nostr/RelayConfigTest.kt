package com.nostrpost.app.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-19: cleartext relays must never be accepted by a release build. */
class RelayConfigTest {

    @Test
    fun releaseAcceptsOnlyTlsRelays() {
        assertTrue(RelayConfig.isValidRelayUrl("wss://relay.example.com", allowCleartext = false))
        assertTrue(RelayConfig.isValidRelayUrl("wss://relay.example.com/ws", allowCleartext = false))
        assertFalse(RelayConfig.isValidRelayUrl("ws://relay.example.com", allowCleartext = false))
        assertFalse(RelayConfig.isValidRelayUrl("http://relay.example.com", allowCleartext = false))
        assertFalse(RelayConfig.isValidRelayUrl("https://relay.example.com", allowCleartext = false))
    }

    @Test
    fun debugAllowsCleartextForLocalDevelopment() {
        assertTrue(RelayConfig.isValidRelayUrl("ws://127.0.0.1:7777", allowCleartext = true))
        assertFalse(RelayConfig.isValidRelayUrl("ftp://relay.example.com", allowCleartext = true))
    }

    @Test
    fun malformedRelaysAreRejected() {
        listOf(
            "",
            "   ",
            "wss://",
            "wss:// relay.example.com",
            "wss://user:pass@relay.example.com",
            "wss://relay.example.com:99999",
        ).forEach { url ->
            assertFalse("should reject '$url'", RelayConfig.isValidRelayUrl(url, allowCleartext = true))
        }
    }
}
