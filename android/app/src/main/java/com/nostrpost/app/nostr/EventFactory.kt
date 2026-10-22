package com.nostrpost.app.nostr

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class UnsignedEvent(
    val pubkey: String, // hex 32 bytes
    val createdAt: Long, // seconds
    val kind: Int,
    val tags: List<List<String>>,
    val content: String
)

data class SignedEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    /** NIP-01 event JSON. Built by hand so serialization is deterministic and dependency-free. */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"id\":\"").append(id).append("\",")
        sb.append("\"pubkey\":\"").append(pubkey).append("\",")
        sb.append("\"created_at\":").append(createdAt).append(',')
        sb.append("\"kind\":").append(kind).append(',')
        sb.append("\"tags\":").append(EventFactory.serializeTags(tags)).append(',')
        sb.append("\"content\":").append(EventFactory.escapeJsonString(content)).append(',')
        sb.append("\"sig\":\"").append(sig).append('"')
        sb.append('}')
        return sb.toString()
    }

    fun toEventArrayJson(): String = toJson()
}

object EventFactory {

    fun createUnsigned(pubkeyHex: String, content: String, kind: Int = 1, tags: List<List<String>> = emptyList()): UnsignedEvent {
        val now = System.currentTimeMillis() / 1000
        return UnsignedEvent(
            pubkey = pubkeyHex.lowercase(),
            createdAt = now,
            kind = kind,
            tags = tags,
            content = content
        )
    }

    /** Canonical serialization per NIP-01: [0, pubkey, created_at, kind, tags, content] */
    fun canonicalJson(event: UnsignedEvent): String {
        // Manual JSON building to guarantee canonical form
        val sb = StringBuilder()
        sb.append('[')
        sb.append(0)
        sb.append(',')
        sb.append('"').append(event.pubkey).append('"')
        sb.append(',')
        sb.append(event.createdAt)
        sb.append(',')
        sb.append(event.kind)
        sb.append(',')
        sb.append(serializeTags(event.tags))
        sb.append(',')
        sb.append(escapeJsonString(event.content))
        sb.append(']')
        return sb.toString()
    }

    internal fun serializeTags(tags: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in tags.indices) {
            if (i > 0) sb.append(',')
            sb.append('[')
            val tag = tags[i]
            for (j in tag.indices) {
                if (j > 0) sb.append(',')
                sb.append(escapeJsonString(tag[j]))
            }
            sb.append(']')
        }
        sb.append(']')
        return sb.toString()
    }

    internal fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun computeId(canonical: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return Hex.encode(hash)
    }

    fun createSigned(unsigned: UnsignedEvent, sigHex: String): SignedEvent {
        val canonical = canonicalJson(unsigned)
        val id = computeId(canonical)
        return SignedEvent(
            id = id,
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = sigHex.lowercase()
        )
    }

    /** Helper for local signing: create unsigned + sign with priv */
    fun signLocally(pubkeyHex: String, content: String, privHex: String): SignedEvent {
        val unsigned = createUnsigned(pubkeyHex, content)
        val canonical = canonicalJson(unsigned)
        val id = computeId(canonical)
        val sig = NostrCrypto.sign(Hex.decode(id), Hex.decode(privHex)).let { Hex.encode(Hex.decode(it)) }
        // Actually NostrCrypto.sign expects hash bytes + priv bytes, returns hex
        // Use direct:
        val sig2 = NostrCrypto.sign(Hex.decode(id), Hex.decode(privHex))
        // dedup: use sig2
        return SignedEvent(id, pubkeyHex, unsigned.createdAt, unsigned.kind, unsigned.tags, unsigned.content, sig2)
    }

    /** Build JSON for nostrsigner: need unsigned event JSON without id/sig */
    fun unsignedToSignerJson(unsigned: UnsignedEvent): String {
        // Amber expects event json with pubkey, created_at, kind, tags, content (no id/sig)
        // We'll send full unsigned shape as per NIP-55: event JSON string
        val obj = JSONObject()
        obj.put("pubkey", unsigned.pubkey)
        obj.put("created_at", unsigned.createdAt)
        obj.put("kind", unsigned.kind)
        obj.put("tags", JSONArray(unsigned.tags.map { JSONArray(it) }))
        obj.put("content", unsigned.content)
        return obj.toString()
    }

    fun signedEventFromJson(json: String): SignedEvent {
        val obj = JSONObject(json)
        return SignedEvent(
            id = obj.getString("id"),
            pubkey = obj.getString("pubkey"),
            createdAt = obj.getLong("created_at"),
            kind = obj.getInt("kind"),
            tags = run {
                val arr = obj.getJSONArray("tags")
                val out = mutableListOf<List<String>>()
                for (i in 0 until arr.length()) {
                    val inner = arr.getJSONArray(i)
                    val tag = mutableListOf<String>()
                    for (j in 0 until inner.length()) tag.add(inner.getString(j))
                    out.add(tag)
                }
                out
            },
            content = obj.getString("content"),
            sig = obj.getString("sig")
        )
    }
}
