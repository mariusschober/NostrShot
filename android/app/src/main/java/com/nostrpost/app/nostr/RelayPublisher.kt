package com.nostrpost.app.nostr

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PublishResult(
    val success: Boolean,
    val relay: String?,
    val error: String?
)

class RelayPublisher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    /**
     * Publish concurrently to all relays, return success if at least one OK true for exact id.
     * Timeout per publish overall ~5-6 seconds.
     */
    suspend fun publish(signed: SignedEvent, relays: List<String>, timeoutMs: Long = 8000): PublishResult = coroutineScope {
        android.util.Log.i("RelayPublisher", "publish id=${signed.id} to ${relays.size} relays")
        if (relays.isEmpty()) return@coroutineScope PublishResult(false, null, "No relays configured")

        val eventJson = signed.toJson()
        // Wrap as ["EVENT", eventObj]
        val payload = JSONArray().apply {
            put("EVENT")
            put(JSONObject(eventJson))
        }.toString()
        android.util.Log.d("RelayPublisher", "payload size=${payload.length}")

        val mutex = Mutex()
        var firstSuccess: String? = null
        val errors = mutableListOf<String>()
        val jobs = mutableListOf<Deferred<Boolean>>()

        for (relay in relays) {
            val d = async(Dispatchers.IO) {
                try {
                    val ok = publishToRelay(relay, signed.id, payload, timeoutMs)
                    if (ok) {
                        mutex.withLock {
                            if (firstSuccess == null) firstSuccess = relay
                        }
                        true
                    } else false
                } catch (e: Exception) {
                    mutex.withLock { errors.add("$relay: ${e.message}") }
                    false
                }
            }
            jobs.add(d)
        }

        try {
            withTimeout(timeoutMs + 500) {
                // Wait until at least one succeeds or all fail
                while (isActive) {
                    if (firstSuccess != null) break
                    val allDone = jobs.all { it.isCompleted }
                    if (allDone) break
                    delay(50)
                }
            }
        } catch (_: TimeoutCancellationException) {
            // timeout
        } finally {
            // Cancel remaining jobs and close sockets? sockets closed per relay
            jobs.forEach { it.cancel() }
        }

        android.util.Log.i("RelayPublisher", "publish result success=$firstSuccess errors=$errors")
        if (firstSuccess != null) {
            PublishResult(true, firstSuccess, null)
        } else {
            PublishResult(false, null, errors.firstOrNull() ?: "All relays failed or timed out")
        }
    }

    private suspend fun publishToRelay(relayUrl: String, expectedId: String, payload: String, timeoutMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(relayUrl).build()
        var done = false
        fun finish(v: Boolean) {
            if (!done) {
                done = true
                if (cont.isActive) cont.resume(v) {}
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val arr = JSONArray(text)
                    if (arr.length() >= 3 && arr.getString(0) == "OK") {
                        val id = arr.getString(1)
                        val ok = arr.getBoolean(2)
                        if (id.equals(expectedId, ignoreCase = true) && ok) {
                            webSocket.close(1000, "done")
                            finish(true)
                        } else if (id.equals(expectedId, ignoreCase = true) && !ok) {
                            webSocket.close(1000, "rejected")
                            finish(false)
                        }
                        // else ignore other OKs
                    }
                } catch (_: Exception) {
                    // ignore parse error
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // ignore
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // if not already succeeded, mark false (unless already done)
                if (!done && !cont.isCompleted) finish(false)
            }
        }

        val ws = client.newWebSocket(request, listener)

        // Timeout handler
        val timer = CoroutineScope(Dispatchers.IO).launch {
            delay(timeoutMs)
            try { ws.close(1000, "timeout") } catch (_: Exception) {}
            finish(false)
        }

        cont.invokeOnCancellation {
            timer.cancel()
            try { ws.cancel() } catch (_: Exception) {}
        }

        // cleanup timer when done
        CoroutineScope(Dispatchers.IO).launch {
            // poll until done then cancel timer
            while (!done && cont.isActive) delay(100)
            timer.cancel()
        }
    }
}

// Extension for suspendCancellableCoroutine with resume helper
private fun <T> CancellableContinuation<T>.resume(value: T, onCancellation: (() -> Unit)?) {
    if (isActive) resumeWith(Result.success(value))
}
