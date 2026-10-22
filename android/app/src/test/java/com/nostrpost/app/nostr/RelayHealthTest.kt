package com.nostrpost.app.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** NOSTRSHOT-30: offline fail-fast and readable per-relay health. */
class RelayHealthTest {

    private val event = SignedEvent(
        id = "aa".repeat(32),
        pubkey = "bb".repeat(32),
        createdAt = 1_700_000_000,
        kind = 1,
        tags = emptyList(),
        content = "hi",
        sig = "cc".repeat(64)
    )

    @Before
    fun clearStore() {
        RelayHealthStore.clear()
    }

    @Test
    fun offlineFailsFastWithoutOpeningSockets() = runBlocking {
        val transport = object : RelayTransport {
            override fun open(url: String, listener: RelaySocketListener): RelaySocket =
                throw AssertionError("must not open sockets while offline")
        }
        val publisher = RelayPublisher(transport) { false }
        val result = publisher.publish(event, listOf("wss://relay.example.com"))
        assertFalse(result.success)
        assertTrue(result.retryable)
        assertTrue(result.error!!.contains("offline", ignoreCase = true))
        assertTrue(result.error!!.contains("draft is saved", ignoreCase = true))
    }

    @Test
    fun successAndFailureAreRecordedPerRelay() {
        RelayHealthStore.recordSuccess("wss://relay.example.com", 42)
        var health = RelayHealthStore.forRelay("wss://relay.example.com")!!
        assertEquals(42L, health.lastLatencyMs)
        assertTrue(health.statusText().contains("last OK"))
        assertEquals(null, health.lastError)

        RelayHealthStore.recordFailure("wss://relay.example.com", "timed out")
        health = RelayHealthStore.forRelay("wss://relay.example.com")!!
        assertEquals("timed out", health.lastError)
        assertTrue(health.statusText().contains("timed out"))
        // A previous success timestamp is retained for context.
        assertTrue(health.lastSuccessAtMillis != null)
    }

    @Test
    fun statusTextHandlesNoAttempt() {
        assertEquals("no attempt yet", RelayHealth("wss://new.example.com").statusText())
    }
}
