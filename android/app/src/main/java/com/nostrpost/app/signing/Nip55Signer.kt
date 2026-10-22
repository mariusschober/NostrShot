package com.nostrpost.app.signing

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * NIP-55 helper - does not use library, implements spec directly for auditability.
 * Supports:
 * - detection via PackageManager queryIntentActivities for nostrsigner:
 * - get_public_key via Intent + permissions
 * - sign_event via ContentResolver (background) + Intent fallback
 */
object Nip55 {

    private const val TAG = "Nip55"

    fun isSignerAvailable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        val infos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        return infos.isNotEmpty()
    }

    fun getSignerLabel(context: Context, packageName: String): String {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    fun getSignerPackages(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        val infos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        return infos.mapNotNull { it.activityInfo?.packageName }.distinct()
    }

    fun createGetPublicKeyIntent(permissionsJson: String? = null): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        intent.putExtra("type", "get_public_key")
        if (permissionsJson != null) {
            intent.putExtra("permissions", permissionsJson)
        }
        return intent
    }

    fun createSignEventIntent(eventJson: String, pubkeyHex: String, signerPackage: String, id: String? = null): Intent {
        // Payload is the event json in Uri after scheme
        val uri = Uri.parse("nostrsigner:${Uri.encode(eventJson)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.`package` = signerPackage
        intent.putExtra("type", "sign_event")
        intent.putExtra("current_user", pubkeyHex.lowercase())
        if (id != null) intent.putExtra("id", id)
        return intent
    }

    fun permissionsForKind1(): String {
        // [{"type":"sign_event","kind":1}]
        val arr = JSONArray()
        val obj = JSONObject()
        obj.put("type", "sign_event")
        obj.put("kind", 1)
        arr.put(obj)
        return arr.toString()
    }

    /**
     * Try ContentResolver background signing. Returns SignedJson or null if needs Intent fallback.
     * Caller must hold pubkeyHex + signerPackage.
     * FIX: Amber's SignerProvider reads projection (2nd param), not selectionArgs, and expects npub.
     * We now send projection = [eventJson, "", npub] and handle both "result" and "signature" columns.
     */
    fun tryContentResolverSign(context: Context, eventJson: String, signerPackage: String, pubkeyHex: String): SignResult? {
        return try {
            val uri = Uri.parse("content://$signerPackage.SIGN_EVENT")
            // Convert hex pubkey to npub for Amber compatibility (Amber's parsePubKey handles hex too, but npub is canonical)
            val npub = try {
                com.nostrpost.app.nostr.Bech32.toNpub(pubkeyHex.lowercase())
            } catch (_: Exception) {
                pubkeyHex.lowercase()
            }
            // NIP-55 spec example passes listOf(eventJson, "", loggedInUserPubkey) as projection
            // Amber's SignerProvider passes projection directly to SignerProviderQuery (not selectionArgs)
            val projection = arrayOf(eventJson, "", npub)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use { c ->
                if (c.count == 0) {
                    Log.d(TAG, "ContentResolver returned empty -> need Intent")
                    return null
                }
                if (!c.moveToFirst()) return null
                // Amber returns rejected cursor with single column "rejected" = "true"
                val colNames = c.columnNames
                if (colNames.contains("rejected")) {
                    val rejectedIdx = c.getColumnIndex("rejected")
                    if (rejectedIdx != -1) {
                        val v = try { c.getString(rejectedIdx) } catch (_: Exception) { null }
                        // If rejected column exists and has value, or even just column presence with single column, treat as rejected
                        if (v == "true" || colNames.size == 1) {
                            Log.d(TAG, "ContentResolver rejected (remembered=false)")
                            return SignResult.Rejected
                        }
                        // If rejected exists but result also exists, still fallback check below
                        if (c.getColumnIndex("result") == -1 && c.getColumnIndex("signature") == -1) {
                            Log.d(TAG, "ContentResolver rejected (no result column)")
                            return SignResult.Rejected
                        }
                    } else {
                        return SignResult.Rejected
                    }
                }
                // Amber now returns MatrixCursor(arrayOf("signature", "event", "result"))
                // Older NIP-55 spec says "result" + "event". Support both.
                var resultIdx = c.getColumnIndex("result")
                if (resultIdx == -1) resultIdx = c.getColumnIndex("signature")
                val eventIdx = c.getColumnIndex("event")
                if (resultIdx == -1 || eventIdx == -1) {
                    Log.w(TAG, "Missing result/signature or event columns: ${colNames.joinToString()}")
                    return null
                }
                val sig = c.getString(resultIdx)
                val signedEventJson = c.getString(eventIdx)
                if (sig.isNullOrBlank() || signedEventJson.isNullOrBlank()) {
                    Log.w(TAG, "Empty sig/event from resolver")
                    return null
                }
                Log.d(TAG, "ContentResolver success via background")
                SignResult.Success(signature = sig, signedEventJson = signedEventJson)
            } ?: run {
                Log.d(TAG, "ContentResolver query returned null -> need Intent (not remembered)")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver error: ${e.message}")
            null
        }
    }

    /** Helper to test if background signing is available without publishing (dummy event) */
    fun isBackgroundAvailable(context: Context, signerPackage: String, pubkeyHex: String): Boolean {
        return try {
            // Create minimal dummy kind 1 event JSON for probe
            val dummyEvent = org.json.JSONObject().apply {
                put("pubkey", pubkeyHex.lowercase())
                put("created_at", System.currentTimeMillis() / 1000)
                put("kind", 1)
                put("tags", org.json.JSONArray())
                put("content", "probe")
            }.toString()
            val res = tryContentResolverSign(context, dummyEvent, signerPackage, pubkeyHex)
            // Success means remembered; null means not remembered (needs Intent); Rejected means explicitly rejected
            res is SignResult.Success
        } catch (_: Exception) { false }
    }
}

sealed class SignResult {
    data class Success(val signature: String, val signedEventJson: String) : SignResult()
    object Rejected : SignResult()
    data class Error(val message: String) : SignResult()
}
