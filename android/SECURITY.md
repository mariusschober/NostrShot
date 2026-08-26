# SECURITY.md — Nostr Post

## Model

Preferred: **external NIP-55 signer** (Amber, etc.). App never receives private key. Local encrypted storage is fallback.

> Local signing necessarily has a larger attack surface than using a dedicated external signer.

## External signer

- Detect via `nostrsigner:` queries.
- Request only `sign_event` kind 1.
- Try `ContentResolver` background if remembered; fallback to `Intent`.
- Verify returned event: kind==1, content byte-exact, tags `[]`, timestamp expected, id == sha256(canonical), Schnorr `verifySchnorr` with pubkey, sig matches `result`.
- Reject on `rejected==true`, missing fields, verification fail.
- Handle signer uninstalled → "Signer unavailable", do not silently fallback.

## Local secret handling

- Import: `nsec1` (Bech32 hrp nsec, 32 bytes) or hex 64 chars, validated, pubkey derived via `secp256k1.pubkeyCreate` (x-only), then encrypted and original input cleared (`fill(0)`).
- Keystore: `ALIAS nostr_wrap_key`, `AES/GCM/NoPadding`, 256-bit, `RANDOMIZED_REQUIRED`, `USER_AUTH_REQUIRED false`. StrongBox attempted if `FEATURE_STRONGBOX_KEYSTORE`, else TEE.
- Ciphertext: `files/nostr_secret.enc` = `iv(12) || ciphertext+tag`. Private file, `MODE_PRIVATE`.
- Decrypt only for signing, `fill(0)` after, not in ViewModel/Compose/savedState.
- Protection level exposed via `KeyInfo` (StrongBox / Hardware-backed / Software) in Settings.

## Backup exclusions

```xml
<!-- backup_rules.xml + data_extraction_rules.xml -->
<exclude domain="file" path="nostr_secret.enc" />
<exclude domain="file" path="datastore/" />
<exclude domain="sharedpref" path="." />
<exclude domain="database" path="." />
```

Keystore wrapping keys do not migrate; ciphertext would be unusable after restore, hence excluded. User retains nsec independently; app is not backup.

## Screenshot / clipboard / logs

- `FLAG_SECURE` on import screen only (blocks screenshots, recents). Composer not secured (content public).
- Clipboard: accept paste, validate, encrypt, clear input; no auto-copy of secrets; no clipboard cleanup that destroys unrelated content.
- Logs policy: dev logs may contain relay URL, event id, timing, signer mode, relay OK; **never** nsec, priv bytes, AES key, plaintext import. Release builds minimal logs. Audited via `grep -R nsec|private|secret` before release.

## Permissions

- `INTERNET` only.
- `queries` for `nostrsigner` scheme (not runtime).

## Threat model

| Threat | Expected |
|---|---|
| Attacker reads app data dir | Cannot recover key without Keystore (hardware-bound). |
| Cloud backup of files | Secret excluded; restore requires re-import. |
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
- `fr.acinq.secp256k1:secp256k1-kmp:0.15.0` (+ jni) — BIP340. Why: smallest proven secp256k1, libsecp256k1 JNI, auditable.
- `bcprov-jdk18on:1.81` — SHA helpers (MessageDigest also used).
Pinned versions, no DI/analytics/network abstractions.

## Verification steps

- `grep -R "Log\." app/src | grep -i -E "nsec|priv|secret"` → empty
- `adb shell run-as com.nostrpost.app ls files` → `nostr_secret.enc` exists, not in `shared_prefs`
- `adb shell dumpsys backup` → excludes
- Publish test: verify Schnorr, then `["OK", id, true]` from damus (see README).

## Reporting

File issues at project repo; do not include private keys in reports. Use test keys (`nsec1a3ttk...` hex `ec56bb...` **TEST KEY — NO VALUE**).
