package com.nostrpost.app.nostr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Connectivity check used to fail fast with "offline" instead of waiting for relay timeouts. */
object NetworkMonitor {
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true // never block publishing because the connectivity API misbehaved
        }
    }
}
