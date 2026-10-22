package com.nostrpost.app.nostr

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one relay attempt. */
sealed class RelayAttempt {
    abstract val relay: String

    data class Accepted(override val relay: String) : RelayAttempt()

    data class Rejected(
        override val relay: String,
        val reason: String
    ) : RelayAttempt() {
        /** NIP-20 relays use a `duplicate:` prefix when they already stored the event. */
        val isDuplicate: Boolean
            get() = reason.trimStart().startsWith("duplicate:", ignoreCase = true) ||
                reason.contains("already have this event", ignoreCase = true)
    }

    data class Timeout(override val relay: String) : RelayAttempt()

    data class Unreachable(
        override val relay: String,
        val detail: String? = null
    ) : RelayAttempt()
}

data class RelayFailure(
    val relay: String,
    val reason: String,
    val kind: Kind
) {
    enum class Kind { REJECTED, TIMEOUT, UNREACHABLE }
}

data class PublishResult(
    val success: Boolean,
    val relay: String?,
    val error: String?,
    val failures: List<RelayFailure> = emptyList(),
    /** True when every responding relay said the event already exists (safe to treat as published). */
    val alreadyPublished: Boolean = false,
    /** Relay failures are transient by nature; the UI should always offer Retry. */
    val retryable: Boolean = true
)

/** Small transport seam so relay completion can be tested deterministically without a network. */
internal interface RelayTransport {
    fun open(url: String, listener: RelaySocketListener): RelaySocket
}

internal interface RelaySocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
    fun cancel()
}

internal interface RelaySocketListener {
    fun onOpen(socket: RelaySocket)
    fun onMessage(text: String)
    fun onFailure(error: Throwable, httpCode: Int?)
    fun onClosed(code: Int, reason: String)
}

private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .build()

class RelayPublisher internal constructor(
    private val transport: RelayTransport,
    private val networkAvailable: () -> Boolean = { true }
) {
    constructor(networkAvailable: () -> Boolean = { true }) :
        this(OkHttpRelayTransport(defaultClient()), networkAvailable)

    /**
     * Publish concurrently to all relays and return success as soon as at least one relay
     * acknowledges the exact event id with `OK true`. All per-relay reasons are aggregated so the
     * UI can explain *why* a publish failed instead of showing a generic timeout.
     */
    suspend fun publish(signed: SignedEvent, relays: List<String>, timeoutMs: Long = 8000): PublishResult =
        coroutineScope {
            if (relays.isEmpty()) {
                return@coroutineScope PublishResult(false, null, "No relays configured")
            }
            if (!networkAvailable()) {
                return@coroutineScope PublishResult(
                    success = false,
                    relay = null,
                    error = "You're offline — your draft is saved. Publish again when you're back online.",
                    retryable = true
                )
            }

            val payload = "[\"EVENT\",${signed.toJson()}]"

            val mutex = Mutex()
            var firstSuccess: String? = null
            val attempts = mutableListOf<RelayAttempt>()
            val jobs = mutableListOf<Deferred<RelayAttempt>>()

            for (rawRelay in relays) {
                // Re-validate and normalize at connect time: an old stored ws:// relay must not
                // be dialled in a release build even if it predates the URL checks in Settings.
                val relay = RelayConfig.normalizeOrNull(rawRelay)
                if (relay == null) {
                    mutex.withLock {
                        attempts.add(
                            RelayAttempt.Unreachable(
                                rawRelay,
                                "insecure or invalid relay URL (wss:// required)"
                            )
                        )
                    }
                    continue
                }
                val job = async(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    val attempt = try {
                        publishToRelay(relay, signed.id, payload, timeoutMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        RelayAttempt.Unreachable(relay, e.message)
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    when (attempt) {
                        is RelayAttempt.Accepted -> RelayHealthStore.recordSuccess(relay, elapsed)
                        is RelayAttempt.Rejected -> RelayHealthStore.recordFailure(relay, attempt.reason.ifBlank { "rejected" })
                        is RelayAttempt.Timeout -> RelayHealthStore.recordFailure(relay, "timed out")
                        is RelayAttempt.Unreachable -> RelayHealthStore.recordFailure(relay, attempt.detail ?: "unreachable")
                    }
                    mutex.withLock {
                        attempts.add(attempt)
                        if (attempt is RelayAttempt.Accepted && firstSuccess == null) {
                            firstSuccess = relay
                        }
                    }
                    attempt
                }
                jobs.add(job)
            }

            try {
                withTimeout(timeoutMs + 500) {
                    while (isActive) {
                        if (firstSuccess != null) break
                        if (jobs.all { it.isCompleted }) break
                        delay(50)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // fall through with whatever completed
            } finally {
                jobs.forEach { it.cancel() }
            }

            val snapshot = mutex.withLock {
                // Guarantee one outcome per relay even if a cancellation beat its timer, so the
                // caller always gets a bounded, explainable result instead of an empty list.
                relays.forEach { rawRelay ->
                    val relay = RelayConfig.normalizeOrNull(rawRelay) ?: rawRelay
                    if (attempts.none { it.relay == relay }) {
                        attempts.add(RelayAttempt.Timeout(relay))
                    }
                }
                attempts.toList()
            }
            val failures = snapshot.filterNot { it is RelayAttempt.Accepted }.map { it.toFailure() }
            val duplicate = snapshot.filterIsInstance<RelayAttempt.Rejected>().firstOrNull { it.isDuplicate }

            when {
                firstSuccess != null -> PublishResult(true, firstSuccess, null, failures)
                duplicate != null -> PublishResult(
                    success = true,
                    relay = duplicate.relay,
                    error = null,
                    failures = failures,
                    alreadyPublished = true
                )
                else -> PublishResult(
                    success = false,
                    relay = null,
                    error = PublishErrorFormatter.message(failures),
                    failures = failures,
                    retryable = true
                )
            }
        }

    private suspend fun publishToRelay(
        relayUrl: String,
        expectedId: String,
        payload: String,
        timeoutMs: Long
    ): RelayAttempt = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        var timer: Job? = null
        var socket: RelaySocket? = null

        // Exactly-once completion: onMessage/onFailure/onClosed/timeout can race on different
        // OkHttp threads. tryResume is safe even if cancellation won the race.
        fun finish(result: RelayAttempt) {
            if (!finished.compareAndSet(false, true)) return
            timer?.cancel()
            if (cont.isActive) cont.resume(result) {}
        }

        val listener = object : RelaySocketListener {
            override fun onOpen(socket: RelaySocket) {
                try {
                    socket.send(payload)
                } catch (e: Exception) {
                    finish(RelayAttempt.Unreachable(relayUrl, e.message))
                }
            }

            override fun onMessage(text: String) {
                val ok = RelayMessageParser.parseOk(text) ?: return
                if (!ok.id.equals(expectedId, ignoreCase = true)) return
                if (ok.accepted) {
                    finish(RelayAttempt.Accepted(relayUrl))
                    try { socket?.close(1000, "done") } catch (_: Exception) {}
                } else {
                    finish(RelayAttempt.Rejected(relayUrl, ok.reason))
                    try { socket?.close(1000, "rejected") } catch (_: Exception) {}
                }
            }

            override fun onFailure(error: Throwable, httpCode: Int?) {
                val detail = buildString {
                    append(error.message ?: error.javaClass.simpleName)
                    if (httpCode != null) append(" (HTTP $httpCode)")
                }
                finish(RelayAttempt.Unreachable(relayUrl, detail))
            }

            override fun onClosed(code: Int, reason: String) {
                if (!finished.get()) {
                    finish(
                        RelayAttempt.Unreachable(
                            relayUrl,
                            "connection closed before OK ($code${if (reason.isBlank()) "" else ": $reason"})"
                        )
                    )
                }
            }
        }

        socket = try {
            transport.open(relayUrl, listener)
        } catch (e: Exception) {
            finish(RelayAttempt.Unreachable(relayUrl, e.message))
            null
        }

        if (!finished.get() && socket != null) {
            timer = CoroutineScope(Dispatchers.IO).launch {
                delay(timeoutMs)
                try { socket.close(1000, "timeout") } catch (_: Exception) {}
                finish(RelayAttempt.Timeout(relayUrl))
            }
        }

        cont.invokeOnCancellation {
            timer?.cancel()
            try { socket?.cancel() } catch (_: Exception) {}
        }
    }
}

private fun RelayAttempt.toFailure(): RelayFailure = when (this) {
    is RelayAttempt.Rejected -> RelayFailure(relay, reason.ifBlank { "rejected without a reason" }, RelayFailure.Kind.REJECTED)
    is RelayAttempt.Timeout -> RelayFailure(relay, "timed out", RelayFailure.Kind.TIMEOUT)
    is RelayAttempt.Unreachable -> RelayFailure(relay, detail ?: "unreachable", RelayFailure.Kind.UNREACHABLE)
    is RelayAttempt.Accepted -> RelayFailure(relay, "accepted", RelayFailure.Kind.UNREACHABLE)
}

/** Turns per-relay attempts into one actionable sentence. */
object PublishErrorFormatter {

    fun message(failures: List<RelayFailure>): String {
        if (failures.isEmpty()) return "Couldn't publish — no relay answered. Your post is still here."
        val rejected = failures.filter { it.kind == RelayFailure.Kind.REJECTED }
        if (rejected.isNotEmpty()) {
            val first = rejected.first()
            val host = hostOf(first.relay)
            val detail = if (rejected.size > 1) " (+${rejected.size - 1} more)" else ""
            return "Relay $host rejected the post: ${first.reason}$detail"
        }
        val timedOut = failures.count { it.kind == RelayFailure.Kind.TIMEOUT }
        val unreachable = failures.count { it.kind == RelayFailure.Kind.UNREACHABLE }
        return buildString {
            append("Couldn't reach a relay (")
            if (timedOut > 0) append("$timedOut timed out")
            if (timedOut > 0 && unreachable > 0) append(", ")
            if (unreachable > 0) append("$unreachable unreachable")
            append("). Your post is still here.")
        }
    }

    internal fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) {
        url
    }
}

private class OkHttpRelayTransport(private val client: OkHttpClient) : RelayTransport {
    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        val request = Request.Builder().url(url).build()
        val socket = OkHttpRelaySocket(client.newWebSocket(request, listener.toOkHttp()))
        return socket
    }

    private fun RelaySocketListener.toOkHttp(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@toOkHttp.onOpen(OkHttpRelaySocket(webSocket))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            this@toOkHttp.onMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // NIP-01 relay messages are text frames.
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@toOkHttp.onFailure(t, response?.code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@toOkHttp.onClosed(code, reason)
        }
    }
}

private class OkHttpRelaySocket(private val webSocket: WebSocket) : RelaySocket {
    override fun send(text: String): Boolean = webSocket.send(text)
    override fun close(code: Int, reason: String) { webSocket.close(code, reason) }
    override fun cancel() = webSocket.cancel()
}


