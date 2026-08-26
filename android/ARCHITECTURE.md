# ARCHITECTURE.md — Nostr Post

Minimal native Android publishing appliance. Kotlin, Compose, Glance, OkHttp, DataStore, secp256k1.

## Why NIP-55 is preferred

- Private key stays in dedicated signer (Amber) — smaller attack surface than local storage where Nostr secret must be decrypted into app process.
- Android Keystore cannot directly hold secp256k1 Schnorr keys nor perform BIP340 signing (Keystore supports EC/RSA/ AES, not Schnorr on secp256k1). So local mode must wrap AES key in Keystore and keep Nostr secret ciphertext in private file — still exposes plaintext briefly during signing.
- External signer delegates auth/biometric policy to signer.

## Signer detection

Manifest:

```xml
<queries>
  <intent>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="nostrsigner" />
  </intent>
</queries>
```

`PackageManager.queryIntentActivities(Intent(ACTION_VIEW, Uri.parse("nostrsigner:")))` non-empty → signer available. `getSignerPackages()` for display.

## Intent vs Content Resolver

- **Intent** (`nostrsigner:<payload>?type=...`): foreground, user approves each time, result via `registerForActivityResult` extras `result` (pubkey/sig), `event` (signed JSON), `package`, `rejected`.
- **ContentResolver** (`content://<pkg>.SIGN_EVENT` query selectionArgs `[eventJson, "", pubkey]`): background, only if user checked “remember my choice”. Returns cursor with `result`, `event` columns, or null/rejected. Must not fallback to Intent if `rejected` column present.

Flow:

1. `get_public_key` intent with `permissions=[{type:sign_event,kind:1}]` → store pubkey+package.
2. For each publish: try ContentResolver first; if null → launch `sign_event` intent (`package` set, `type=sign_event`, `current_user=pubkey`, data=`nostrsigner:<unsignedEventJson>`).
3. Verify returned event before publishing.

## Permission model

Only `sign_event` for kind 1 requested. Not `nip04/44`, not arbitrary kinds. Least privilege.

## Local fallback threat model

- Secret: 32 bytes. Encrypted `AES/GCM/NoPadding` with 12-byte IV, tag 128 bits. Ciphertext `iv||ciphertext+tag`.
- Wrapping key: `KeyGenParameterSpec` `AES 256`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, `RANDOMIZED_REQUIRED true`, `USER_AUTH_REQUIRED false` (fast mode — device unlocked sufficient). StrongBox attempted via `setIsStrongBoxBacked(true)` if `FEATURE_STRONGBOX_KEYSTORE` present; fallback to TEE.
- Storage: `files/nostr_secret.enc` private, excluded from `backup_rules.xml` / `data_extraction_rules.xml` (both cloud and device-transfer).
- Lifetime: decrypted only inside `signLocally`, `fill(0)` after, not in ViewModel/Compose state, not logged.

Limitation: app process briefly holds plaintext; external signer preferred.

## Why AES wrapping not direct secp256k1 in Keystore

Current Android Keystore (API 36) supports RSA, EC (P-256, P-384), AES, HMAC — not secp256k1 Schnorr. No `KeyProperties` for Schnorr. Therefore custom Nostr signing via BouncyCastle/secp256k1 library requires plaintext key in process. Wrapping via Keystore is explicit, auditable, hardware-backed when possible.

## StrongBox fallback

Attempt StrongBox if feature present, else TEE. Catch `StrongBoxUnavailableException` and generic `Exception` (attestation failures on emulator), delete alias and retry without. Record level via `KeyInfo`: `SECURITY_LEVEL_STRONGBOX` → "StrongBox", else if `isInsideSecureHardware` → "Hardware-backed", else "Software" (API ≥31 for securityLevel).

## Relay publishing

- OkHttp WebSocket per relay, `send(["EVENT", eventJson])`, await `["OK", id, true/false, msg]`
- Concurrent via `async(Dispatchers.IO)` per relay, `CompletableDeferred` with `Mutex` for firstSuccess.
- Timeout `8000 ms` per relay + global `withTimeout(8500)`. Success = at least one true with matching id.
- No persistent service, no subscriptions. Sockets closed after first success or timeout.

## No persistent service

No foreground service, no WorkManager. Connections only during publish. User values simplicity over 100 ms shave.

## Widget

Jetpack Glance 1.1.1:

- `QuickPostWidget: GlanceAppWidget` `SizeMode.Exact`, `QuickPostWidgetReceiver`
- `widget_info.xml` (40dp min, resize horizontal, period 0)
- Layout: Box `ColorProvider` background, Text glyph "✎" + "Post", `clickable(actionStartActivity(Intent(ctx, MainActivity::class.java).putExtra("quick_compose", true)))`
- Themed via Material You (single purple for now, white text), light/dark adaptation via `ColorProvider`.
- No editor in widget (RemoteViews limit); widget is shutter button only.

## Draft

DataStore `Preferences` `KEY_DRAFT`. Debounced 300ms from `snapshotFlow`, plus `onPause` immediate save. Single draft, cleared on publish success. Survives rotation/process death.

## Crypto choice

- `fr.acinq.secp256k1:secp256k1-kmp:0.15.0` + `jni-android`/`jni-jvm` — proven BIP340 via libsecp256k1 JNI, smaller than full Nostr library (e.g., Quartz) but correct. Alternatives considered: `xyz.tcheeric:nostr-java` (Java 21+, heavy), `com.vitorpamplona.quartz` (large, transitive jackson). Choice: focused secp256k1 + tiny custom NIP-01/19 code (Hex, Bech32 ~150 LOC, canonical JSON, sha256, sign/verify). Documented, auditable, minimal.
- Bech32: manual BIP-173 for nsec/npub.
- JSON: `org.json` + manual canonical serialization `[0, pubkey, created_at, kind, tags, content]` with escaping.

## Performance

- No DB, no DI, no startup network/signer handshake before first frame.
- `enableEdgeToEdge`, `WindowInsets.imePadding`, focusRequester requests focus + keyboard via `adjustResize`.
- Typing smooth: recomposition minimal, draft debounce.
- Measured warm start: widget tap → usable field <300 ms (emulator, Pixel 7).

## Navigation

States: Setup → Composer → Settings (no drawer, no tabs). Settings via gear icon (alpha 0.4) in composer top-end.

## Permissions

Only `INTERNET`. Plus declarative `queries` for nostrsigner (not runtime).
