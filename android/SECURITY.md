# SECURITY.md — Nostr Post

## Model

Preferred: **external NIP-55 signer** (Amber, etc.). App never receives private key. Local encrypted storage is fallback.

> Local signing necessarily has a larger attack surface than using a dedicated external signer.

## External signer

- Detect via `nostrsigner:` queries.
- Request only `sign_event` kind 1.
- Try `ContentResolver` background if remembered; fallback to `Intent`.
- Verify returned event against the exact `UnsignedEvent` sent: kind==1, content byte-exact, empty tags, exact `created_at`, pubkey, id == sha256(canonical), Schnorr `verifySchnorr`; the `result` signature must match `event.sig`. Any change is rejected, not just logged.
- Reject on `rejected==true`, missing fields, verification fail.
- Handle signer uninstalled → "Signer unavailable", do not silently fallback.

## Local secret handling

- Import: `nsec1` (Bech32 hrp nsec, 32 bytes) or hex 64 chars, parsed directly into a `ByteArray` (no extra hex String), validated, pubkey derived via `secp256k1.pubkeyCreate` (x-only), encrypted, then the byte buffer is zeroed in `finally`. The full npub must be confirmed before saving.
- The Clipboard is cleared after a successful import only when it still contains the imported key; unrelated clipboard content is never touched. JVM `String` input cannot be securely zeroed — this is a bounded best-effort improvement, not a guarantee.
- Keystore: `ALIAS nostr_wrap_key`, `AES/GCM/NoPadding`, 256-bit, `RANDOMIZED_REQUIRED`, `USER_AUTH_REQUIRED false`. StrongBox attempted if `FEATURE_STRONGBOX_KEYSTORE`, else TEE.
- Reading a secret **never** creates or deletes the wrapping key. If the key is missing/invalidated or the ciphertext fails authentication, a typed `SecretUnavailableException` asks the user to re-import; only an explicit Remove Key deletes the alias. Writes go to a temp file, `fsync`, then atomic rename.
- Ciphertext: `files/nostr_secret.enc` = `iv(12) || ciphertext+tag`. Private file, `MODE_PRIVATE`.
- Decrypt only for signing, `fill(0)` after, not in ViewModel/Compose/savedState.
- Protection level exposed via `KeyInfo` (StrongBox / Hardware-backed / Software) in Settings.

## Backup

`android:allowBackup="false"`. Nothing (secret, draft, relays) leaves the device through Android backup or device transfer. Keystore wrapping keys do not migrate and the ciphertext would be unusable after restore; the user must keep their nsec independently.

## Screenshot / clipboard / logs

- `FLAG_SECURE` on import screen only (blocks screenshots, recents). Composer not secured (content public).
- Clipboard: accept paste, validate, encrypt; clear the primary clip only if it still equals the just-imported secret; no auto-copy of secrets and no cleanup of unrelated content.
- Logs policy: dev logs may contain relay URL, event id, timing, signer mode, relay OK; **never** nsec, priv bytes, AES key, plaintext import. Release builds minimal logs. Audited via `grep -R nsec|private|secret` before release.

## Permissions

- `INTERNET` only.
- `queries` for `nostrsigner` scheme (not runtime).

## Threat model

| Threat | Expected |
|---|---|
| Attacker reads app data dir | Cannot recover key without Keystore (hardware-bound). |
| Cloud backup / device transfer | Disabled entirely (`allowBackup=false`); restore requires re-import. |
| Malicious signer returns altered event | Event verification catches (id/sig/content mismatch). |
| Relay receives forged event | Already verified locally before send. |
| Logs collected | No private key. |
| Screenshot during import | Blocked by FLAG_SECURE. |
| Device stolen unlocked — external mode | Security depends on signer policy. |
| Device stolen unlocked — local mode | Keystore without user-auth allows signing while unlocked; future could add biometric per-sign (not in V1 fast mode). |
| Process compromised during local sign | Exposure window minimized (decrypt → sign → clear). |

Inherent limitation: locally signing app must expose plaintext secp256k1 secret to process briefly because Keystore cannot do Schnorr.

## Dependency review

- `androidx.*` (Compose, Glance, DataStore, lifecycle) — UI persistence.
- `kotlinx-coroutines`, `kotlinx-serialization-json` — async/json.
- `okhttp:4.12.0` — WebSocket.
- `fr.acinq.secp256k1:secp256k1-kmp:0.24.0` (+ jni) — BIP340, 16 KB-aligned native library for Android 15+.
- `bcprov-jdk18on:1.81` — SHA helpers (MessageDigest also used).
Pinned versions, no DI/analytics/network abstractions.

## Verification steps

- `grep -R "Log\." app/src | grep -i -E "nsec|priv|secret"` → empty
- `adb shell run-as com.nostrpost.app ls files` → `nostr_secret.enc` exists, not in `shared_prefs`
- `adb shell dumpsys backup` → excludes
- Publish test: verify Schnorr, then `["OK", id, true]` from damus (see README).

## Reporting

File issues at project repo; do not include private keys in reports. Use test keys (`nsec1a3ttk...` hex `ec56bb...` **TEST KEY — NO VALUE**).
