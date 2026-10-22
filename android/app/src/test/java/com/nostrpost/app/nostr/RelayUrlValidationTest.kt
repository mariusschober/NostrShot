package com.nostrpost.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-24: relay input must be a real URL, normalized without corrupting paths/queries. */
class RelayUrlValidationTest {

    @Test
    fun normalizesHostAndSchemeOnly() {
        assertEquals(
            "wss://relay.example.com/MyRelay",
            RelayConfig.normalizeOrNull(" WSS://Relay.Example.COM/MyRelay ", allowCleartext = false)
        )
        assertEquals(
            "wss://relay.example.com/ws?token=AbC",
            RelayConfig.normalizeOrNull("wss://relay.example.com/ws?token=AbC", allowCleartext = false)
        )
    }

    @Test
    fun removesOnlyARootTrailingSlash() {
        assertEquals("wss://relay.example.com", RelayConfig.normalizeOrNull("wss://relay.example.com/"))
        assertEquals("wss://relay.example.com/ws/", RelayConfig.normalizeOrNull("wss://relay.example.com/ws/"))
    }

    @Test
    fun rejectsInvalidAndNonRelayUrls() {
        listOf(
            "wss://",
            "wss:// relay.example.com",
            "wss://user:pass@relay.example.com",
            "https://relay.example.com",
            "ws://relay.example.com",
            "wss://relay.example.com:99999",
            "relay.example.com"
        ).forEach { url ->
            assertNull("should reject '$url'", RelayConfig.normalizeOrNull(url, allowCleartext = false))
        }
    }

    @Test
    fun debugAllowsCleartextButReleaseDoesNot() {
        assertEquals("ws://127.0.0.1:7777", RelayConfig.normalizeOrNull("ws://127.0.0.1:7777", allowCleartext = true))
        assertNull(RelayConfig.normalizeOrNull("ws://127.0.0.1:7777", allowCleartext = false))
    }

    @Test
    fun duplicateDetectionIgnoresCaseAndTrailingSlash() {
        val existing = listOf("wss://relay.example.com")
        assertTrue(RelayConfig.isDuplicate("WSS://RELAY.EXAMPLE.COM/", existing))
        assertFalse(RelayConfig.isDuplicate("wss://other.example.com", existing))
    }

    @Test
    fun sanitizeNormalizesDeduplicatesAndKeepsOrder() {
        val result = RelayConfig.sanitize(
            listOf(
                "wss://relay.example.com/",
                "WSS://RELAY.EXAMPLE.COM",
                "wss://nos.lol",
                "not a relay",
                "wss://relay.example.com/second"
            ),
            allowCleartext = false
        )
        assertEquals(
            listOf(
                "wss://relay.example.com",
                "wss://nos.lol",
                "wss://relay.example.com/second"
            ),
            result
        )
    }
}
