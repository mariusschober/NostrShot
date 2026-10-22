package com.nostrpost.app.nostr

import com.nostrpost.app.BuildConfig

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
    fun isValidRelayUrl(url: String, allowCleartext: Boolean = BuildConfig.DEBUG): Boolean {
        val parsed = try {
            java.net.URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        if (parsed.rawUserInfo != null) return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        val schemeAllowed = when (scheme) {
            "wss" -> true
            "ws" -> allowCleartext
            else -> false
        }
        if (!schemeAllowed) return false
        val host = parsed.host
        if (host.isNullOrBlank()) return false
        if (host.contains(' ') || url.any { it.isWhitespace() }) return false
        val port = parsed.port
        return port == -1 || port in 1..65535
    }

    fun normalize(url: String): String = url.trim().removeSuffix("/")
}
