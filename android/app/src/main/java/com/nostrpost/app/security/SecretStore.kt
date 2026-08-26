package com.nostrpost.app.security

interface SecretStore {
    suspend fun hasSecret(): Boolean
    suspend fun storeSecret(privKey32: ByteArray, protectionLevelOut: ((String)->Unit)? = null)
    suspend fun loadSecret(): ByteArray // caller must clear after use
    suspend fun clearSecret()
    suspend fun getProtectionLevel(): String?
}
