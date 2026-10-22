package com.nostrpost.app.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * NOSTRSHOT-04: publish must surface per-relay rejection reasons and offer a retryable outcome.
 * NOSTRSHOT-18 extends this with concurrent callback coverage.
 */
class RelayPublisherTest {

    private val event = SignedEvent(
        id = "ab".repeat(32),
        pubkey = "cd".repeat(32),
        createdAt = 1_700_000_000,
        kind = 1,
        tags = emptyList(),
        content = "hello",
        sig = "ef".repeat(64)
    )

    private class FakeTransport : RelayTransport {
        val sockets = mutableListOf<FakeSocket>()
        var replyForPayload: ((String) -> String?)? = null

        override fun open(url: String, listener: RelaySocketListener): RelaySocket {
            val socket = FakeSocket(url, listener)
            socket.autoReply = replyForPayload
            sockets += socket
            listener.onOpen(socket)
            return socket
        }
    }

    private class FakeSocket(
        val url: String,
        val listener: RelaySocketListener
    ) : RelaySocket {
        val sent = mutableListOf<String>()
        var closed = false
        var cancelled = false
        var autoReply: ((String) -> String?)? = null

        override fun send(text: String): Boolean {
            sent += text
            autoReply?.invoke(text)?.let { listener.onMessage(it) }
            return true
        }

        override fun close(code: Int, reason: String) {
            closed = true
        }

        override fun cancel() {
            cancelled = true
        }

        fun deliver(text: String) = listener.onMessage(text)
        fun fail(error: Throwable = IOException("connection refused")) =
            listener.onFailure(error, null)
    }

    private fun transportReplying(reply: String): Pair<RelayPublisher, FakeTransport> {
        val transport = FakeTransport()
        val publisher = RelayPublisher(transport)
        // send() is invoked from onOpen during open(); install the reply beforehand.
        transport.replyForPayload = { reply }
        return Pair(publisher, transport)
    }

    @Test
    fun acceptedByOneRelayIsSuccess() = runBlocking {
        val reply = """["OK","${event.id}",true,""]"""
        val (publisher, _) = transportReplying(reply)
        val result = publisher.publish(event, listOf("wss://relay.example.com"), timeoutMs = 1000)
        assertTrue(result.success)
        assertEquals("wss://relay.example.com", result.relay)
        assertEquals(null, result.error)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun rejectionReasonIsPreservedAndRetryable() = runBlocking {
        val reply = """["OK","${event.id}",false,"blocked: not allowed"]"""
        val (publisher, _) = transportReplying(reply)
        val result = publisher.publish(event, listOf("wss://relay.example.com"), timeoutMs = 1000)
        assertFalse(result.success)
        assertTrue(result.retryable)
        assertEquals(1, result.failures.size)
        assertEquals(RelayFailure.Kind.REJECTED, result.failures[0].kind)
        assertEquals("blocked: not allowed", result.failures[0].reason)
        assertNotNull(result.error)
        assertTrue("error must include the relay reason", result.error!!.contains("blocked: not allowed"))
    }

    @Test
    fun duplicateRejectionCountsAsAlreadyPublished() = runBlocking {
        val reply = """["OK","${event.id}",false,"duplicate: already have this event"]"""
        val (publisher, _) = transportReplying(reply)
        val result = publisher.publish(event, listOf("wss://relay.example.com"), timeoutMs = 1000)
        assertTrue("a duplicate is already stored, so treat as success", result.success)
        assertTrue(result.alreadyPublished)
        assertEquals(null, result.error)
    }

    @Test
    fun unreachableRelayIsReported() = runBlocking {
        val transport = object : RelayTransport {
            override fun open(url: String, listener: RelaySocketListener): RelaySocket {
                throw IOException("no route to host")
            }
        }
        val publisher = RelayPublisher(transport)
        val result = publisher.publish(event, listOf("wss://down.example.com"), timeoutMs = 1000)
        assertFalse(result.success)
        assertEquals(RelayFailure.Kind.UNREACHABLE, result.failures.single().kind)
        assertTrue(result.error!!.contains("unreachable", ignoreCase = true))
    }

    @Test
    fun timeoutIsReportedAsTimeout() = runBlocking {
        // Fake transport opens a socket that never answers.
        val transport = object : RelayTransport {
            override fun open(url: String, listener: RelaySocketListener): RelaySocket {
                val socket = object : RelaySocket {
                    override fun send(text: String) = true
                    override fun close(code: Int, reason: String) {}
                    override fun cancel() {}
                }
                listener.onOpen(socket)
                return socket
            }
        }
        val publisher = RelayPublisher(transport)
        val result = publisher.publish(event, listOf("wss://slow.example.com"), timeoutMs = 120)
        assertFalse(result.success)
        assertEquals(RelayFailure.Kind.TIMEOUT, result.failures.single().kind)
        assertTrue(result.error!!.contains("timed out", ignoreCase = true))
    }

    @Test
    fun oneSuccessWinsEvenWhenAnotherRelayRejects() = runBlocking {
        val transport = object : RelayTransport {
            override fun open(url: String, listener: RelaySocketListener): RelaySocket {
                val socket = object : RelaySocket {
                    override fun send(text: String): Boolean {
                        if (url.contains("good")) {
                            listener.onMessage("""["OK","${event.id}",true,""]""")
                        } else {
                            listener.onMessage("""["OK","${event.id}",false,"rate-limited: slow down"]""")
                        }
                        return true
                    }
                    override fun close(code: Int, reason: String) {}
                    override fun cancel() {}
                }
                listener.onOpen(socket)
                return socket
            }
        }
        val publisher = RelayPublisher(transport)
        val result = publisher.publish(
            event,
            listOf("wss://bad.example.com", "wss://good.example.com"),
            timeoutMs = 1000
        )
        assertTrue(result.success)
        assertEquals("wss://good.example.com", result.relay)
        assertEquals(1, result.failures.size)
    }

    @Test
    fun emptyRelayListFailsWithoutNetwork() = runBlocking {
        val publisher = RelayPublisher(object : RelayTransport {
            override fun open(url: String, listener: RelaySocketListener): RelaySocket =
                throw AssertionError("must not open a socket")
        })
        val result = publisher.publish(event, emptyList())
        assertFalse(result.success)
        assertEquals("No relays configured", result.error)
    }

    @Test
    fun errorFormatterShowsRelayHostAndReason() {
        val message = PublishErrorFormatter.message(
            listOf(
                RelayFailure(
                    "wss://relay.example.com/ws",
                    "blocked: spam",
                    RelayFailure.Kind.REJECTED
                )
            )
        )
        assertTrue(message.contains("relay.example.com"))
        assertTrue(message.contains("blocked: spam"))
    }
}
