package com.nostrpost.app.nostr

import java.util.concurrent.ConcurrentHashMap

data class RelayHealth(
    val relay: String,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
    val lastLatencyMs: Long? = null
) {
    fun statusText(nowMillis: Long = System.currentTimeMillis()): String = when {
        lastError != null && lastSuccessAtMillis == null -> "failed: $lastError"
        lastError != null -> "last attempt failed: $lastError"
        lastSuccessAtMillis != null -> {
            val ageSec = ((nowMillis - lastSuccessAtMillis) / 1000).coerceAtLeast(0)
            val age = when {
                ageSec < 60 -> "${ageSec}s ago"
                ageSec < 3600 -> "${ageSec / 60}m ago"
                else -> "${ageSec / 3600}h ago"
            }
            buildString {
                append("last OK $age")
                lastLatencyMs?.let { append(" · ${it}ms") }
            }
        }
        else -> "no attempt yet"
    }
}

/** In-memory health of the relays used by this install. Never persisted, never contains notes. */
object RelayHealthStore {
    private val health = ConcurrentHashMap<String, RelayHealth>()

    fun recordSuccess(relay: String, latencyMs: Long?) {
        health[relay] = RelayHealth(
            relay = relay,
            lastSuccessAtMillis = System.currentTimeMillis(),
            lastError = null,
            lastLatencyMs = latencyMs
        )
    }

    fun recordFailure(relay: String, reason: String) {
        val previous = health[relay]
        health[relay] = RelayHealth(
            relay = relay,
            lastSuccessAtMillis = previous?.lastSuccessAtMillis,
            lastError = reason,
            lastLatencyMs = previous?.lastLatencyMs
        )
    }

    fun snapshot(): List<RelayHealth> = health.values.sortedBy { it.relay }

    fun forRelay(relay: String): RelayHealth? = health[relay]

    fun clear() = health.clear()
}
