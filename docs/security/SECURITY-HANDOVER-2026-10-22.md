# Security handover — NostrShot — 2026-10-22

Branch: `security/2026-10-22`, created from `main` @ `cf28e96`. Pushed without a PR. No merge, deploy or CI run.
The commits were cherry-picked (`-x`) from the local review branch `improve/2026-10-22`. That branch is not pushed and is waiting for review.

## Fixes on this branch

| Commit | Item | What it does |
|---|---|---|
| 48a580f | NOSTRSHOT-01 (High) | Removes the unauthenticated `auto_content` intent extra from the exported `MainActivity`. Any app could use it to make NostrShot sign and publish a note silently in release builds. It also removes the debug `auto_import_nsec` key-injection path. A source regression test (`IntentAutomationSecurityTest`) guards against them coming back. The launcher, share and quick-compose paths are unchanged. |
| 4c3d63f | NOSTRSHOT-02 | Reading the secret no longer creates, replaces or deletes the Android Keystore wrapping key (`nostr_wrap_key`). A missing, invalidated or corrupt key, or an IO error, raises a typed `SecretUnavailableException` with a "re-import your nsec" message. Writes are atomic: temp file, fsync, then rename. Only an explicit `clearSecret()` deletes the key. The on-disk format (`iv‖ct`) and the alias are unchanged, so existing installs keep working. |
| f9ce99a | NOSTRSHOT-38 | Sets `allowBackup="false"` and `fullBackupContent="false"`; the extraction rules still exclude the secret and DataStore. **Behaviour change:** nothing is restored by Android backup or device transfer, so users must keep their nsec. |

### Not on this branch (conflicts with `main`, skipped; on `improve/2026-10-22` only)
- **NOSTRSHOT-07** (`268d522`): verify that an external NIP-55 signer returned exactly the unsigned event. Conflict in `ComposerViewModel.kt`.
- **NOSTRSHOT-19** (`5dd2cdd`): reject cleartext `ws://` relays in release builds, and set `usesCleartextTraffic=false`. Conflict in `RelayPublisher.kt`.
- **NOSTRSHOT-26** (`cf094f4`): import the nsec as bytes and clear the clipboard if it still holds the key. Conflict in `MainActivity.kt` and `SignerRepository.kt`.

These depend on earlier non-security refactors (NOSTRSHOT-04/05/06/18) and ship with the `improve/` branch.

## Tests (Linux VM, this branch)
- `android`: `./gradlew testDebugUnitTest lintDebug assembleDebug` passes. **18 unit tests**, 0 failures (baseline 7, plus `IntentAutomationSecurityTest`, `SecretStoreTest` and `BackupPolicyTest`). Lint has 0 errors, and the debug APK builds.
- Not run: device tests of Keystore/StrongBox behaviour (key invalidation after a lock-screen change), backup/restore on a phone, and the macOS app (needs a Mac).

## Not done / needs Marius
- Review and merge are up to Marius. No PR was opened.
- On a device: test that a key invalidated by removing the lock screen shows the re-import message, and that it never silently creates a new key.
- macOS security items (Keychain error states, key replacement confirmation, NIP-46) need a Mac.
