# Nostr Post — Minimal Nostr Publishing Appliance

> **Open → Type → Publish → Gone.**

A radically minimal native Android app for publishing `kind:1` text notes to Nostr. No feed, no timeline, no likes, no DMs — just a capture tool that feels like a camera shutter.

```
tap widget → composer focused → type → Publish → signed → relay OK → app closes → home
```

[![Android](https://img.shields.io/badge/Android-minSdk%2028%20%7C%20target%2036-green)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-blue)]()
[![Compose](https://img.shields.io/badge/Compose-BOM%202026.06.00-purple)]()
[![License](https://img.shields.io/badge/license-MIT-black)]()

---

## Philosophy

Most social apps fuse **creation** and **consumption**. Nostr Post separates them.

- After setup: **one launch + typing + one tap**.
- No navigation, no tabs, no feed to scroll.
- If a feature isn’t needed for *composing / signing / publishing / recovering / configuring infrastructure*, it doesn’t belong.

Working name **Nostr Post** is isolated to `strings.xml:app_name` + `applicationId`.

---

## Features

### Composer — the whole app

- Edge-to-edge, `BasicTextField` 23sp/32lh, generous padding, no toolbar.
- Auto-focus + `imePadding()` → keyboard rides publish button, no jump.
- Haptics: `KEYBOARD_TAP` on first char, `CONFIRM` on Publish; cursor pulse `1→0.55` 900 ms.
- Time-aware placeholder `08:42 · Morning — What's on your mind?`
- Single draft (DataStore debounced 300 ms + `onPause` save, survives rotation / process death, cleared only on success).
- `Publish` enabled only when `trim().isNotEmpty()` && `!publishing`; spring `AnimatedContent` → `✓ Published` → `finishAndRemoveTask()` 420 ms.
- `Back` preserves draft, no dialog. `FLAG_SECURE` only on import screen.

### Signing

**Recommended — NIP-55 external signer (Amber, etc.) — app never sees `nsec`:**

1. Install [Amber](https://github.com/greenart7c3/Amber) (`com.greenart7c3.nostrsigner`)
2. Create/import identity in Amber
3. `Nostr Post → Use Android signer → Connect` → in Amber **☑ Remember my choice / Fully trust kind 1** → back
4. Next publishes use **background `ContentResolver`** (`content://<pkg>.SIGN_EVENT` with `projection=[eventJson,"",npub]`) → no Amber UI, **device unlock only** (no Amber PIN each time). Fallback to `Intent` `nostrsigner:` (`type=sign_event`, `current_user`) only if not remembered.

> **Fix 2026-08-24:** corrected `query(uri, projection, …)` (was `selectionArgs`) + handle `signature`/`result` + `npub` conversion. Before, background always returned `null` → always fell back to Intent → PIN every time.

**Fallback — local encrypted nsec:**

- Import `nsec1…` (Bech32 32 bytes, hrp `nsec`) or `64-char hex`, validated via `secp256k1.secKeyVerify`.
- Derive x-only pubkey, verify, encrypt `AES/GCM/NoPadding` 256-bit, `iv12 || ciphertext+tag` in `files/nostr_secret.enc` (`MODE_PRIVATE`), excluded from `backup_rules.xml` + `data_extraction_rules.xml` (cloud + device-transfer).
- Wrapping key: `AndroidKeyStore` `AES`, `BLOCK_MODE_GCM`, `RANDOMIZED_REQUIRED true`, `USER_AUTH_REQUIRED false` (fast mode — device unlocked sufficient), `StrongBox` if `FEATURE_STRONGBOX_KEYSTORE` else TEE, record `StrongBox / Hardware-backed / Software` via `KeyInfo` (`API31+ securityLevel`). Decrypt only inside `signLocally`, `fill(0)` after.

### Relays

Concurrent `OkHttp` `WebSocket` to defaults (diverse, free, current):

- `wss://relay.damus.io`
- `wss://nos.lol`
- `wss://relay.nostr.band`
- `wss://relay.primal.net`

Editable in Settings: add `wss://` (allow `ws://` debug), remove, reset defaults. Validate `wss://`. Send `["EVENT", eventObj]`, await `["OK", id, true, msg]`, `timeout 8s` per relay + global `8500 ms`, first `true` with matching `id` = success, close others. No persistent service/WorkManager.

### System capture — not an app

- **Splash** `Theme.SplashScreen` `installSplashScreen()` 120 ms, icon = widget glyph.
- **Share target** `ACTION_SEND text/plain` → any app Share → composer prefilled `take(560)`.
- **Quick Settings tile** `QuickPostTileService` (`BIND_QUICK_SETTINGS_TILE`, `PendingIntent` for 14+) → swipe down → tap → composer. Add via `adb shell cmd statusbar add-tile custom(com.nostrpost.app/.tile.QuickPostTileService)`.
- **App shortcuts** long-press icon → `New post` (`quick_compose`).
- **Widget** `Glance 1.1.1` `QuickPostWidget` `SizeMode.Exact`, `1×1` `ColorProvider #6B4EFF` → `✎ Post`, `clickable(actionStartActivity(Intent(ctx, MainActivity).putExtra("quick_compose",true)))`, `widget_quick_post.xml` 40dp, `resizeMode horizontal`. Light/dark.
- **Predictive back** `enableOnBackInvokedCallback=true`, draft survives.

### Settings

`· · ·` top-right (alpha 0.22) → `SettingsScreen`:

- Identity: `npub1abc…xyz` truncated + `Copy npub` + `Change signer` / `Remove key` + `Test one-tap` (probes background signing, shows `✓ One-tap ready — device unlock only` vs `✗ Still asks PIN → Fix` + `Open Amber`).
- Relays list + add/remove/reset.
- About: `1.0.0`, no analytics.

---

## Tech stack (pinned)

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin 2.3.21 | Native |
| UI | Jetpack Compose BOM 2026.06.00, Material3 1.4.0, `activity-compose 1.13.0`, `lifecycle-runtime-compose 2.10.0`, `core-splashscreen 1.0.1` | Edge-to-edge, dynamic color |
| Storage | `datastore-preferences 1.2.1` | Single draft + prefs, no DB |
| Widget | `glance-appwidget 1.1.1` | System shutter |
| Crypto | `fr.acinq.secp256k1:secp256k1-kmp:0.15.0` + `jni-android 0.15.0` | BIP-340 via `libsecp256k1` JNI, auditable, smaller than `quartz`/`nostr-java` |
| Net | `okhttp 4.12.0` | WebSocket |
| Async | `coroutines-android 1.10.2` | `StateFlow`, `withTimeout` |
| Desugar | `desugar_jdk_libs 2.1.5` | `java.time` on API 28 |
| Build | AGP 9.1.1, Gradle 9.3.1, Kotlin Compose plugin 2.3.21 | Modern |
| Test | JUnit 4.13.2, Robolectric 4.16.1, `kotlinx-coroutines-test` | JVM secp tests |

Removed for leanness (former bloat): `bcprov-jdk18on` (replace with `MessageDigest`), `material-icons-extended`, `glance-material3`, `ui-text-google-fonts`, `kotlinx-serialization-json`, `commons-compress`, `work-runtime`, `sherpa-onnx` (Canary voice removed for stable — see Roadmap).

---

## Project structure

```
NostrShotAndroid/
├─ app/
│  ├─ src/main/
│  │  ├─ AndroidManifest.xml          # INTERNET only, nostrsigner queries, MainActivity singleTop, widget, tile, shortcuts
│  │  ├─ java/com/nostrpost/app/
│  │  │  ├─ MainActivity.kt           # Splash, share handle, signer pre-warm, ViewModel, Nav (setup/import/composer/settings)
│  │  │  ├─ composer/                 # ComposerScreen (haptics, pulse, ime), ComposerViewModel (draft, publish, relay)
│  │  │  ├─ signing/                  # Nip55Signer (detect, get_public_key, ContentResolver+Intent), SignerRepository
│  │  │  ├─ security/                 # AndroidKeystoreSecretStore (AES wrap, StrongBox), SecretStore
│  │  │  ├─ nostr/                    # Hex, Bech32 (BIP-173), NostrCrypto (secp), EventFactory, EventVerifier, Relay*, RelayConfig
│  │  │  ├─ storage/                  # Preferences (DataStore)
│  │  │  ├─ settings/                 # SetupScreen, ImportScreen (FLAG_SECURE), SettingsScreen
│  │  │  ├─ widget/                   # QuickPostWidget (Glance)
│  │  │  ├─ tile/                     # QuickPostTileService
│  │  │  └─ ui/Theme.kt               # Material3 dynamic light/dark
│  │  └─ res/
│  │     ├─ drawable/ic_edit.xml, ic_settings.xml
│  │     ├─ values/ colors, strings (app_name isolated), themes (Theme.NostrPost, Splash)
│  │     └─ xml/ backup_rules, data_extraction_rules, widget_quick_post, shortcuts
│  ├─ build.gradle.kts                # namespace com.nostrpost.app, compileSdk 36, minSdk 28, target 36, resConfigs en, bundle splits
│  └─ proguard-rules.pro
├─ dist/                              # Usable artifacts (kept after clean)
│  ├─ NostrPost-debug.apk             # 39M debug signed (adb install -r)
│  ├─ NostrPost-release-unsigned.apk  # 7.9M universal (R8, needs apksigner)
│  └─ NostrPost-release.aab           # 8.5M, per-device 2.6M via bundletool
├─ gradle/wrapper/
├─ gradle.properties                  # Xmx3g, parallel, caching, fullMode, artProfiles
├─ settings.gradle.kts
├─ local.properties                   # sdk.dir=/Users/schober/Documents/Circadiano/.android-sdk (shared with Circadiano)
├─ README.md, ARCHITECTURE.md, SECURITY.md, PLAN.md, CONTEXT.md
└─ .gitignore
```

---

## Build

**Prereqs:** JDK 17 (Temurin 17.0.19 at `.toolchains/jdk-17.0.19+10` or `JAVA_HOME`), Android SDK `platforms;android-36`, `build-tools;36.0.0`, `emulator` at `/Users/schober/Documents/Circadiano/.android-sdk` (shared — see Context), `minSdk 28` (Android 9+).

```bash
export JAVA_HOME=/Users/schober/Documents/Circadiano/.toolchains/jdk-17.0.19+10/Contents/Home
export ANDROID_HOME=/Users/schober/Documents/Circadiano/.android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease bundleRelease
# Debug: app/build/outputs/apk/debug/app-debug.apk (39M, per-device ~2.6M via aab)
# Release unsigned: app/build/outputs/apk/release/app-release-unsigned.apk (7.9M, download 6.3M)
# AAB: app/build/outputs/bundle/release/app-release.aab (8.5M) → dist/
```

SDK check: `sdkmanager --list_installed` → `build-tools;36.0.0`, `platforms;android-36`, `emulator 36.6.11`, `platform-tools 37.0.0`.

**Size note:** `debug 39M` is unshrunk (4 dex + 4 ABIs native 5.1M). `release 7.9M universal` → `bundletool` per-device **2.63–2.69 MB** (`splits/base-arm64_v8a 1.33M + master`). Before lean: debug 76M, release 9.2M; removed `bcprov 1.25M`, `icons-extended`, `glance-material3`, `google-fonts`, `serialization`, `resConfigs en`.

---

## Run

```bash
./gradlew installDebug
adb shell am start -n com.nostrpost.app/.MainActivity
# Widget: long-press home → Widgets → Nostr Post → 1×1
adb shell cmd statusbar add-tile custom\(com.nostrpost.app/.tile.QuickPostTileService\)
# Share: any app Share → Nostr Post (text/plain)
```

---

## Testing

```bash
./gradlew testDebugUnitTest   # 7 tests: Bech32, pubkey 79BE..., canonical 23sp, sign/verify, tamper
./gradlew lint
```

**Manual acceptance — external signer:**

1. Install Amber, create **TEST KEY — NO VALUE** identity.
2. Install Nostr Post (debug).
3. `Use Android signer` → in Amber **☑ Remember / Fully trust kind 1** → `Settings → Test one-tap` → `✓ One-tap ready`.
4. Add widget, tap → type `Publishing should feel like capturing a thought.` → `Publish` → no Amber UI (background) → `✓ Published` → closes → verify `wss://relay.damus.io` `REQ {"ids":[id]}` → found.

**Manual acceptance — local:**

1. `adb shell pm clear com.nostrpost.app` → `Store key on device` → paste `nsec1a3ttkdlgyscf6e6vdym0zqev0szt8v4wxdu0qpcruxegws04xvcsqttmj5` (`ec56bb37e824309d674c6936f1032c7c04b3b2ae3378f00703e1b28741f53331` TEST)
2. Type → Publish → relay OK → `adb shell run-as com.nostrpost.app ls files/nostr_secret.enc` exists, not plaintext; `adb backup` excludes.

**WebSocket probe (no app):**

```bash
pip3 install websocket-client
python3 -c "import websocket,json,ssl; ws=websocket.create_connection('wss://relay.damus.io',sslopt={'cert_reqs':0},timeout=5); ws.send(json.dumps(['REQ','t',{'ids':['<id>']}])); ws.settimeout(5); print(ws.recv())"
```

---

## Security

See `SECURITY.md`. Highlights:

- Only `INTERNET` (+ declarative `queries` nostrsigner) — transitive `WAKE_LOCK etc.` from `work` removed.
- `FLAG_SECURE` only on import.
- `nsec` never in logs/prefs/backup/ViewModel — `grep Log | grep -i nsec` empty.
- Event verified before publish: `kind==1`, `content` byte-exact, `id==sha256(canonical)`, `verifySchnorr`.
- Keystore wrapping `AES/GCM` + `StrongBox` → `TEE` fallback, `fill(0)` after sign.

---

## Customization

- Rename: `res/values/strings.xml:app_name` + `app/build.gradle.kts:applicationId`.
- Relays: `nostr/RelayConfig.kt:DEFAULT_RELAYS` or Settings UI.

---

## Roadmap — next

- **Voice (removed for stable, ~198M):** `sherpa-onnx` `canary-180m-flash` `int8` (127M enc + 71M dec), `AudioRecord` 16k mono → `OfflineRecognizer` `en→en`, one-time `WorkManager` `dataSync` download (146M tar, `Range` resume, `Wakelock/Wifilock`), bottom sheet `Download 198MB?` with Wi-Fi/cellular confirm, `RECORD_AUDIO` runtime, `files/canary/` excluded. Bugs fixed: `AudioCapture.updateWavHeader` truncation, `rememberCoroutineScope` fragility. Branch kept in git history.
- Play publish: `bundleRelease` → Play Console → 2 screenshots (1080×2400) + 512 icon + Data safety “No data”.
- Baseline Profiles + `isMinifyEnabled` for debug? Not needed.

---

## License

MIT — internal prototype unless `LICENSE` added.
