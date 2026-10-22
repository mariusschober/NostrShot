package com.nostrpost.app.nostr

import com.nostrpost.app.BuildConfig
import java.net.URI

object RelayConfig {
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://relay.primal.net"
    )

    /**
     * Relays must use TLS. Cleartext `ws://` is only accepted in debug builds so local relay
     * development is possible; release builds never transmit notes over an unencrypted socket.
     */
    fun isValidRelayUrl(url: String, allowCleartext: Boolean = BuildConfig.DEBUG): Boolean =
        normalizeOrNull(url, allowCleartext) != null

    /**
     * Normalizes a relay URL, or returns null if it is not a valid relay endpoint.
     *
     * Only the scheme and host are lowercased; paths are case-sensitive on real servers and
     * query strings may carry access tokens, so both are preserved.
     */
    fun normalizeOrNull(url: String, allowCleartext: Boolean = BuildConfig.DEBUG): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (uri.rawUserInfo != null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val schemeAllowed = when (scheme) {
            "wss" -> true
            "ws" -> allowCleartext
            else -> false
        }
        if (!schemeAllowed) return null
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        var path = uri.rawPath.orEmpty()
        if (path == "/") path = ""
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return buildString {
            append(scheme).append("://").append(host)
            if (port != -1) append(':').append(port)
            append(path)
            append(query)
        }
    }

    fun normalize(url: String): String = normalizeOrNull(url) ?: url.trim().removeSuffix("/")

    /** True if [candidate] already exists in [relays], comparing normalized URLs case-insensitively. */
    fun isDuplicate(candidate: String, relays: List<String>): Boolean {
        val normalized = normalizeOrNull(candidate) ?: return false
        return relays.any { existing ->
            normalizeOrNull(existing)?.equals(normalized, ignoreCase = true) == true
        }
    }

    /** Normalizes, validates and de-duplicates a relay list while preserving order. */
    fun sanitize(relays: List<String>, allowCleartext: Boolean = BuildConfig.DEBUG): List<String> {
        return relays.mapNotNull { normalizeOrNull(it, allowCleartext) }
            .distinctBy { it.lowercase() }
    }
}
