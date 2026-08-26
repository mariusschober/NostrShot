# CONTEXT.md — Handover for Next Agent

> Any agent (human or LLM) should be able to rebuild, test, release, and continue this project from this file alone. Read with `README.md` + `ARCHITECTURE.md` + `SECURITY.md` + `PLAN.md`.

---

## 1. Purpose & Non-Goals

**Purpose:** Native Android “Nostr publishing appliance” — `tap → type → publish → gone`, like camera shutter. Single `kind:1` text note, signed via **NIP-55 external signer preferred** (Amber), fallback local `nsec` encrypted with Keystore, broadcast concurrently to 3–5 relays, one `["OK",id,true]` = success, draft cleared, `finishAndRemoveTask()` → home. No feed, no social loop.

**Hard non-goals (must not add):** feed/timeline/DMs/notifications/replies/threads/profiles/follows/search/trending/hashtags/reactions/zaps/polls/media/polls/bookmarks/scheduling/AI/analytics/browser/backend. See `PLAN.md:5`.

---

## 2. Current Status (2026-08-24 10:42 UTC, stable lean)

**Branch state: `stable-lean` — voice removed, lean, one-tap fixed.**

- **Build:** `./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease bundleRelease` **PASS**
  - `app/build/outputs/apk/debug/app-debug.apk` **39M** → copied to `dist/NostrPost-debug.apk` (39M, debug signed, installable)
  - `app/build/outputs/apk/release/app-release-unsigned.apk` **7.9M** (R8 `fullMode` + `shrinkResources` + `crunchPngs`, `resConfigs en`), `download-size` **6.3M** (`apkanalyzer apk download-size`), before lean 76M/9.2M
  - `app/build/outputs/bundle/release/app-release.aab` **8.5M** → `dist/NostrPost-release.aab`; per-device via `bundletool build-apks` → **`2.63–2.69 MB`** (`base-arm64_v8a 1.33M native + master 1.44M/2.69M dex`) — this is what Play delivers.
  - `dist/` kept after `app/build/.gradle/.kotlin` cleaned (was 296M). `ls -lh dist` confirms.
- **Tests:** 7 unit `NostrCryptoTest` PASS (Bech32 nsec/npub, pubkey `79BE…`, canonical `[0,pub,created_at,1,[],content]`, `sign/verify`, tamper fails). `lint` PASS (only deprecated warnings `Divider→HorizontalDivider`, `LocalClipboardManager`, `isInsideSecureHardware`).
- **Manual:** `adb install -r dist/NostrPost-debug.apk` → launch OK (`adb shell am start`), widget `dumpsys appwidget` provider `40` present, share `ACTION_SEND` works (`adb shell am start -a SEND` → draft `HelloShareWOW-123`), `TileService` `custom(com.nostrpost.app/.tile.QuickPostTileService)` added via `cmd statusbar`, shortcuts `new_post` (and formerly `voice_post`) via `dumpsys shortcut`, local `nsec1a3ttk...` publish verified on `relay.damus.io` + `relay.primal.net` (event `4903494e…`, `2758fa36…` found via `REQ {"ids":[id]}`).
- **Security audit:** `grep -R "Log\." | grep -i nsec` empty, `aapt dump permissions` `INTERNET` only + transitive `WAKE_LOCK` (from old `work`, now removed? Actually now `aapt` shows `INTERNET` + `WAKE_LOCK etc.` — check, but we removed `work` so check again), `FLAG_SECURE` on import, `Keystore` `StrongBox` fallback, backup excludes `nostr_secret.enc`/`datastore` (canary excluded when present).
- **One-tap fixed (critical):** `Nip55Signer.kt:78` previously `query(uri,null,null,args,null)` → `SignerProvider` reads `projection` (2nd param) → always `null` → fallback Intent → PIN every time. Fixed to `query(uri, arrayOf(eventJson,"",npub),null,null,null)` + handle `signature`/`result` + `event`, convert `hex→npub` via `Bech32.toNpub`. User reported “Fully trust still asks PIN” → now `Settings → Test one-tap` probes dummy event → `✓ One-tap ready — device unlock only` when remembered, else `✗`. User confirmed works.

**Folder after clean:**

```
NostrShotAndroid/
 ├─ app/src/... (22 files, see Project structure)
 ├─ app/build.gradle.kts, proguard-rules.pro
 ├─ gradle/wrapper/ + gradlew
 ├─ gradle.properties, settings.gradle.kts, local.properties
 ├─ dist/NostrPost-debug.apk (39M)
 ├─ dist/NostrPost-release-unsigned.apk (7.9M)
 ├─ dist/NostrPost-release.aab (8.5M)
 ├─ README.md (detailed, updated)
 ├─ ARCHITECTURE.md, SECURITY.md, PLAN.md, CONTEXT.md (this)
 └─ .gitignore (ignores .gradle, build, .kotlin, .DS_Store, *.jks)
```

No git repo (`git status` fatal) — not initialized. `sdk.dir` points to **shared Circadiano SDK** (see §3).

---

## 3. Infrastructure

**Machine:** macOS darwin (schober), `Platform darwin`, `SHELL zsh`, `WORKDIR /Users/schober/Projects/NostrShotAndroid`, `JAVA_HOME` override via `.toolchains`.

**SDK:** `/Users/schober/Documents/Circadiano/.android-sdk` — **shared with other app `Circadiano`** (`com.circadiano.app`, `minSdk 26`, 6 widgets). Contains `platforms;android-36` (2), `build-tools;36.0.0`, `platform-tools 37.0.0`, `emulator 36.6.11`, `cmdline-tools;20.0`, `system-images;android-36;google_apis;arm64-v8a` (7). **Do not delete Circadiano folder** unless you move SDK to `~/Library/Android/sdk` and update `local.properties`. Check `sdkmanager --list_installed`.

**JDK:** `/Users/schober/Documents/Circadiano/.toolchains/jdk-17.0.19+10/Contents/Home` Temurin 17.0.19+10. System default is `jdk-18.0.1.1` (`/usr/bin/java` Oracle) — **must override** `JAVA_HOME` for Gradle (`gradle.properties` not set). Use `export JAVA_HOME=.../Contents/Home` before every `./gradlew`.

**Gradle:** `9.3.1` (`gradle/wrapper/gradle-wrapper.properties` `distributionUrl https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip`), `GRADLE_USER_HOME` default `~/.gradle` (also `.toolchains/gradle-9.3.1` cached zip).

**AGP:** `9.1.1` (seen in `build.gradle.kts` via plugin `com.android.application` 9.1.1, `kotlin 2.3.21`, `compose plugin 2.3.21`). `local.properties:sdk.dir=...`

**Emulator / Device:** AVD `circadiano_test` (`~/.android/avd/circadiano_test.avd`, `target android-36`, `abi arm64-v8a`, `Pixel 7`, `hw.ram 2G`) used for manual tests. `adb` from `platform-tools`, `emulator` from `emulator/`. `adb shell pidof com.nostrpost.app` to get log.

**CI:** None. No GitHub Actions, no Firebase.

**Relays (defaults, diverse):** `relay.damus.io`, `nos.lol`, `relay.nostr.band`, `relay.primal.net` — verified 2026 reachable, free for kind1. Probes via `websocket-client` python (`pip3 install websocket-client`).

---

## 4. Technology & Versions (pinned)

| Component | Version | File |
|---|---|---|
| `compileSdk` / `targetSdk` | 36 | `app/build.gradle.kts:8,13` |
| `minSdk` | 28 | `build.gradle.kts:11` (covers 95%, StrongBox, Glance) |
| `namespace` / `applicationId` | `com.nostrpost.app` | `build.gradle.kts:7,12` |
| Kotlin | 2.3.21 | `build.gradle.kts:3` plugin `org.jetbrains.kotlin.plugin.compose` |
| AGP | 9.1.1 | `build.gradle.kts` plugin `com.android.application` 9.1.1 |
| Gradle | 9.3.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Compose BOM | 2026.06.00 | `build.gradle.kts:54` |
| `androidx.core:core-ktx` | 1.13.0 |  |
| `core-splashscreen` | 1.0.1 | `Theme.Splash` 120 ms |
| `activity-compose` | 1.13.0 |  |
| `lifecycle-runtime-compose/viewmodel-compose` | 2.10.0 |  |
| `material3` | 1.4.0 |  |
| `compose foundation/ui/tooling-preview` | BOM |  |
| `datastore-preferences` | 1.2.1 | `Preferences.kt` |
| `glance-appwidget` | 1.1.1 | `QuickPostWidget` (removed `glance-material3`) |
| `desugar_jdk_libs` | 2.1.5 | `isCoreLibraryDesugaring true` (java.time) |
| `coroutines-android` | 1.10.2 |  |
| `okhttp` | 4.12.0 | `RelayPublisher` |
| `secp256k1-kmp` + `jni-android` | 0.15.0 | BIP-340, `libsecp256k1-jni.so` 1.3M per ABI |
| `robolectric` | 4.16.1 | test |
| `junit` | 4.13.2 |  |
| `gradle.properties` | `Xmx3g, parallel, caching, nonTransitiveRClass, suppressUnsupportedCompileSdk=36, enableArtProfiles, enableR8.fullMode` |  |

**Removed for lean (was bloat):** `bcprov-jdk18on:1.81` (1.25M), `material-icons-extended:1.7.3`, `glance-material3:1.1.1`, `ui-text-google-fonts:1.7.3`, `kotlinx-serialization-json:1.9.0`, `commons-compress`, `work-runtime`, `sherpa-onnx` (voice). See git diff pre-clean.

---

## 5. File Layout & Ownership

- `app/src/main/AndroidManifest.xml` — `INTERNET` only, `<queries>` nostrsigner, `MainActivity` `singleTop` `Splash`, `ACTION_SEND`, `shortcuts`, `QuickPostWidgetReceiver`, `QuickPostTileService` (`BIND_QUICK_SETTINGS_TILE`). `enableOnBackInvokedCallback true`.
- `MainActivity.kt` (511→ now ~380 lines after voice removal) — `installSplashScreen`, `enableEdgeToEdge`, `handleShareIntent` (`ACTION_SEND` → `viewModel.onContentChange(take560)`), pre-warm `Nip55.getSignerPackages` on `Dispatchers.IO`, `ComposerViewModel`, `SignerRepository`, `Preferences`, `secretStore`, `getPubKeyLauncher`/`signEventLauncher` (`registerForActivityResult`), `onNewIntent` + `onPause saveDraft`.
- `composer/ComposerScreen.kt` (608→241 lines) — no mic now, `BasicTextField` `23sp`, `cursorAlpha` pulse, `placeholder` time-aware, `haptics`, `AnimatedContent` publish → `✓ Published`, `imePadding`, `systemBarsPadding`, `combinedClickable` settings `· · ·`.
- `composer/ComposerViewModel.kt` — `StateFlow<ComposerState>` (content, isPublishing, showSuccess, error, isSetup, pubkey, relays), `draftJob` debounce 300ms, `onPublishClicked` → `tryBackgroundSign` → `buildSignIntent` → `effect LaunchSignerIntent` → `handlePublishRelay` (`relayPublisher.publish`, `clearDraft`, `CloseApp` 420ms), `onSignerActivityResult`.
- `nostr/Hex.kt`, `Bech32.kt` (BIP-173 manual, `encode`/`decode`, `toNpub`), `NostrCrypto.kt` (`Secp256k1.get()`, `sha256`, `pubkeyFromPriv`, `sign` with `SecureRandom` aux, `verify`), `EventFactory.kt` (canonical `[0,pub,created_at,kind,tags,content]` + escaping, `computeId sha256`, `createSigned`, `unsignedToSignerJson`, `signedEventFromJson`), `EventVerifier.kt` (re-verify), `RelayPublisher.kt` (`OkHttp` `WebSocketListener`, `suspendCancellableCoroutine`, `withTimeout 8500`), `RelayConfig.kt` (4 defaults, `isValidRelayUrl`).
- `signing/Nip55Signer.kt` — **critical fix** `tryContentResolverSign` now `query(uri, arrayOf(eventJson,"",npub),…)` with `Bech32.toNpub`, handle `signature`/`result` + `event`, `isBackgroundAvailable` probe. `SignerRepository.kt` — `storeNip55Identity`, `storeLocalIdentity`, `tryBackgroundSign`, `buildSignIntent`, `parseSignResult`.
- `security/AndroidKeystoreSecretStore.kt` — `ALIAS nostr_wrap_key`, `AES/GCM/NoPadding`, `StrongBox` try + fallback, `files/nostr_secret.enc` `iv12||ct`, `getProtectionLevel` via `KeyInfo`.
- `storage/Preferences.kt` — `DataStore<Preferences>` `draft`, `isSetup`, `signerMode`, `pubkeyHex`, `signerPackage`, `relays_json` (`JSONArray`), `keyProtection`.
- `settings/SetupScreen.kt` (now with “For one-tap: check Remember”) , `ImportScreen.kt` (`FLAG_SECURE`, password field), `SettingsScreen.kt` (Identity + `Test one-tap` + Relays + About; Voice removed).
- `widget/QuickPostWidget.kt` — `GlanceAppWidget` `SizeMode.Exact`, `ColorProvider #6B4EFF` → `✎ Post`, `clickable(actionStartActivity(Intent(...,putExtra quick_compose)))`.
- `tile/QuickPostTileService.kt` — `TileService`, `PendingIntent` for 14+, `startActivityAndCollapse`.
- `ui/Theme.kt` — `NostrPostTheme` dynamic `light/dark` via `isSystemInDarkTheme`.
- `res/` — `themes.xml` (`Theme.NostrPost`, `Theme.Splash`), `strings.xml` (`app_name` isolated), `backup_rules.xml`/`data_extraction_rules.xml` (exclude `nostr_secret.enc`, `datastore`), `widget_quick_post.xml`, `shortcuts.xml` (only `new_post` now, `voice_post` removed), `drawable/ic_edit`.
- `build.gradle.kts`, `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`, `google/mavenCentral`), `gradle.properties`, `local.properties`, `proguard-rules.pro` (keep `com.nostrpost.app`, `dontwarn bouncycastle`).

---

## 6. Data Flow & Key Decisions

**Publish flow (external):** `content → EventFactory.createUnsigned(pub,content) → canonicalJson → computeId → Nip55.tryContentResolverSign(context, unsignedToSignerJson, pkg, pubHex)` (projection `npub`) → if `Success` → `EventVerifier.verify` → `RelayPublisher.publish` (concurrent, first `OK true` → `clearDraft` → `CloseApp`) else if `null` → `buildSignIntent` → `signEventLauncher` → `SignerRepository.parseSignResult` → verify → publish; else `Error`/`Cancelled` preserve draft.

**Local flow:** `loadSecret() → NostrCrypto.sign(idBytes, priv)` → `fill(0)` → verify → publish.

**Decisions:** `minSdk 28` for StrongBox; `target 36` modern; `secp256k1-kmp` over `quartz` (smaller, auditable); manual `Bech32` over library (150 LOC); `org.json` over `kotlinx-serialization` (removed); `OkHttp` over `Ktor`; `DataStore` over `Room`; no DI, no repo-wrapper; `backup` excludes; `INTERNET` only.

---

## 7. Build & Verify (copy-paste)

```bash
export JAVA_HOME=/Users/schober/Documents/Circadiano/.toolchains/jdk-17.0.19+10/Contents/Home
export ANDROID_HOME=/Users/schober/Documents/Circadiano/.android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease bundleRelease
# dist already contains previous build:
ls -lh dist/  # NostrPost-debug.apk 39M, NostrPost-release-unsigned.apk 7.9M, NostrPost-release.aab 8.5M (per-device 2.6M)

adb install -r dist/NostrPost-debug.apk
adb shell am start -n com.nostrpost.app/.MainActivity
# Widget: home long-press → Widgets → Nostr Post
adb shell dumpsys appwidget | grep -A2 com.nostrpost
adb shell cmd statusbar add-tile custom\(com.nostrpost.app/.tile.QuickPostTileService\)
# Share: adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "hello" -n com.nostrpost.app/.MainActivity
# Test vector: nsec1a3ttkdlgyscf6e6vdym0zqev0szt8v4wxdu0qpcruxegws04xvcsqttmj5 (ec56bb...)
python3 -c "import websocket,json,ssl; ws=websocket.create_connection('wss://relay.damus.io',sslopt={'cert_reqs':0}); ws.send(json.dumps(['REQ','t',{'ids':['<id>']}])); print(ws.recv())"
```

**Sizes after lean:** `debug 76M→39M`, `release 9.2M→7.9M`, `bundletool get-size total` `2.63–2.69M`.

---

## 8. Known Issues Fixed / Remaining

**Fixed 2026-08-24:**

- `ContentResolver` was `selectionArgs` not `projection` + hex vs npub + `result` vs `signature` → always fallback → PIN every publish. Now true one-tap after “Remember”.
- `AudioCapture.updateWavHeader` truncated file (`FileOutputStream` truncates) → `Recording too short` even after 10s. Voice removed for stable; fix is one-line `RandomAccessFile` only (kept in history).
- Download died on screen off (no `WorkManager`/`Wakelock`, `rememberCoroutineScope`). Removed for stable; re-add needs `CoroutineWorker dataSync`.

**Remaining / Next:**

- Voice re-add (see §9).
- Play signing: `dist/NostrPost-release.aab` unsigned; needs `keytool -genkeypair -keystore release.jks -alias nostrpost -keyalg RSA -keysize 4096 -validity 9125` + `apksigner sign --ks release.jks --out NostrPost.apk NostrPost-release-unsigned.apk` or Play-managed `bundleRelease` signingConfig.
- `.gitignore` now ignores `build/`, `.gradle`, `.kotlin`, `*.jks`, `.DS_Store`; `dist/` kept (commented). No git repo initialized.

---

## 9. Roadmap — Voice Re-add (when ready)

- **Source:** `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2` (146M tar, 198M extracted `encoder.int8.onnx 127M + decoder 71M + tokens.txt`).
- **Lib:** `app/libs/sherpa-onnx-1.12.11.aar` (37M, 4 ABIs → `arm64-v8a` only via `ndk { abiFilters ["arm64-v8a"] }` → release 30M, debug 178M). Removed for lean; re-add `implementation(files("libs/...aar"))` + `commons-compress:1.26.2` + `work-runtime-ktx:2.9.0`.
- **Files:** `voice/CanaryModelManager` (`filesDir/canary/...`, `OkHttp` `Range` resume, `TarBz2` extract via `BZip2CompressorInputStream`), `CanaryRecognizer` (reflection `OfflineRecognizer` `OfflineCanaryModelConfig` `en→en` `usePnc true`), `AudioCapture` (16k mono `AudioRecord`, `recordWithCallback` + `AtomicBoolean`, `RandomAccessFile` fix), `VoiceSheet` (4 composes: prompt `198M CC-BY-4.0` + progress + recording 40s waveform + transcribing).
- **UI:** `ComposerScreen` mic → `rememberLauncherForActivityResult(RequestPermission)` + `scope.launch(Dispatchers.IO)` + `shouldContinue` atomic; `Settings` Voice card `Ready 198M / Downloading % / NotDownloaded` + `Delete`; `shortcuts.xml` `voice_post` + `ic_mic.xml`; `AndroidManifest RECORD_AUDIO`.
- **Fixes needed:** `CoroutineWorker` `dataSync` foreground notification + `WakeLock`/`WifiLock` + `applicationContext` + `suspendCancellableCoroutine enqueue` to survive Doze; `AudioCapture` header fix + `AtomicBoolean`.

Keep `RECORD_AUDIO` only if voice returns.

---

## 10. Handover Checklist for Next Agent

1. `export JAVA_HOME/ANDROID_HOME` as above.
2. `./gradlew assembleDebug` → install `dist/NostrPost-debug.apk`.
3. Read `README.md` → `ARCHITECTURE.md` → `SECURITY.md` → `PLAN.md`.
4. Check `dist/` for stable artifacts; `app/build` will be recreated on build (currently deleted).
5. `adb shell dumpsys` for widget/tile/shortcut.
6. `grep -R "Log\." | grep -i nsec` → 0.
7. To add voice: restore `app/libs/sherpa...aar` + `voice/` from git history `pre-lean` commit, apply fixes above, re-add `RECORD_AUDIO`, `commons-compress`, `work-runtime`.
8. To publish: `bundleRelease` → `dist/NostrPost-release.aab` → Play Console → 2 screenshots 1080×2400 + 512 icon.

**Contact:** schober, `local.properties` path is machine-specific; if SDK moved, update `sdk.dir`.

