package com.nostrpost.app.nostr

object RelayConfig {
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://relay.primal.net"
    )

    fun isValidRelayUrl(url: String): Boolean {
        val t = url.trim()
        if (t.isEmpty()) return false
        // Prefer wss://, allow ws:// for dev
        return t.startsWith("wss://") || t.startsWith("ws://")
    }

    fun normalize(url: String): String = url.trim().removeSuffix("/")
}
