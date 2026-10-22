package com.nostrpost.app.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** NOSTRSHOT-18: concurrent relay callbacks must complete exactly once without throwing. */
class RelayPublisherConcurrencyTest {

    private val event = SignedEvent(
        id = "12".repeat(32),
        pubkey = "34".repeat(32),
        createdAt = 1_700_000_000,
        kind = 1,
        tags = emptyList(),
        content = "race",
        sig = "56".repeat(64)
    )

    @Test
    fun acceptAndFailureRacingCompleteExactlyOnce() = runBlocking {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                var futures = emptyList<java.util.concurrent.Future<*>>()
                val transport = object : RelayTransport {
                    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
                        val socket = object : RelaySocket {
                            override fun send(text: String): Boolean {
                                val ok = Callable {
                                    listener.onMessage("""["OK","${event.id}",true,""]""")
                                }
                                val fail = Callable<Unit> {
                                    listener.onFailure(java.io.IOException("boom"), null)
                                }
                                futures = pool.invokeAll(listOf(ok, fail)).map { it }
                                return true
                            }

                            override fun close(code: Int, reason: String) {}
                            override fun cancel() {}
                        }
                        listener.onOpen(socket)
                        return socket
                    }
                }
                val result = RelayPublisher(transport)
                    .publish(event, listOf("wss://race.example.com"), timeoutMs = 1000)

                // Surfacing exceptions from the callback threads is the point of this test:
                // a double-resume would throw IllegalStateException here.
                futures.forEach { it.get(2, TimeUnit.SECONDS) }
                assertTrue("exactly one attempted outcome", result.failures.size <= 1)
                if (result.success) {
                    assertTrue(result.failures.isEmpty())
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun duplicateOKDeliveryDoesNotDoubleResume() = runBlocking {
        val transport = object : RelayTransport {
            override fun open(url: String, listener: RelaySocketListener): RelaySocket {
                val socket = object : RelaySocket {
                    override fun send(text: String): Boolean {
                        listener.onMessage("""["OK","${event.id}",true,""]""")
                        listener.onMessage("""["OK","${event.id}",true,""]""")
                        listener.onClosed(1000, "done")
                        listener.onFailure(java.io.IOException("late failure"), null)
                        return true
                    }

                    override fun close(code: Int, reason: String) {}
                    override fun cancel() {}
                }
                listener.onOpen(socket)
                return socket
            }
        }
        val result = RelayPublisher(transport)
            .publish(event, listOf("wss://double.example.com"), timeoutMs = 1000)
        assertTrue(result.success)
        assertTrue(result.failures.isEmpty())
        assertFalse(result.alreadyPublished)
    }
}
