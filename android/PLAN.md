# PLAN.md — Nostr Post

> Radically minimal Nostr publishing appliance. `tap → type → publish → gone`

---

## Phase 0 — Environment & research
**Objective:** Confirm toolchain, SDK, and spec realities before writing app code.
**Implementation:**
- Inspect local Android SDK at `/Users/schober/Documents/Circadiano/.android-sdk` (platform 36, build-tools 36.0.0, emulator present).
- JDK 17 toolchain at `.toolchains/jdk-17.0.19+10` (Temurin 17.0.19). Use `JAVA_HOME=.../Contents/Home` for all Gradle invocations. Avoid JDK 18-only APIs.
- Gradle 9.3.1 + AGP 9.1.1 (from Circadiano) — modern baseline. Keep `minSdk 28`, `targetSdk 36`, `compileSdk 36` (Android 9+ covers 95%+ devices, enables Keystore StrongBox, biometrics, modern Glance).
- Research NIP-01 (kind 1), NIP-19 (bech32), NIP-55 (Android signer). NIP-55 uses `nostrsigner:` intents + `content://<signerPackage>.SIGN_EVENT` ContentResolver background path. Must declare `<queries><intent data scheme="nostrsigner">`.
- Research Amber current package `com.greenart7c3.nostrsigner` and generic NIP-55 flow. Research Android Keystore: cannot directly do secp256k1 Schnorr; must wrap AES key in Keystore, store ciphertext in app-private file.
- Research StrongBox: `setIsStrongBoxBacked(true)` with fallback to TEE. Research Glance 1.1.1 APIs for widget. Research OkHttp WebSocket for relays.
- Relay defaults chosen after checking current reachability (2026): `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.nostr.band`, `wss://relay.primal.net` — 4 relays, diverse operators, all free for kind 1.
- **Validation:** `sdkmanager --list_installed` succeeds, `./gradlew --version` with JDK 17 prints Gradle 9.3.1.

## Phase 1 — NIP-55 proof (vertical slice)
**Objective:** Prove external signing works before building UI polish.
**Implementation:**
- Create minimal activity that on button tap builds unsigned kind-1 JSON (`[0, pubkey, created_at, kind, tags, content]` → sha256 → event id), then calls `get_public_key` via intent, stores `pubkey`+`package`, then `sign_event` via ContentResolver first (background), fall back to Intent.
- Use hardcoded test content `"Publishing should feel like capturing a thought."`
- Verify returned `signedEventJson` locally (BIP340 verify with secp256k1-kmp).
- **Validation:** Manual test with Amber installed — install Amber, create disposable identity, install proof app, see `sign_event` succeed, parse `event` extra and `result` signature.

## Phase 2 — Project shell
**Objective:** Create native Android project that builds.
**Implementation:**
- Single module `:app` with Kotlin 2.3.21, Compose BOM 2026.06.00, `androidx.activity:activity-compose`, `material3`, `datastore-preferences`, `glance-appwidget`, `kotlinx-coroutines-android`, `kotlinx-serialization-json`, `okhttp:4.12.0`, `fr.acinq.secp256k1:secp256k1-kmp:0.15.+` (+ jni-android/jumbo), `androidx.core:core-ktx`.
- `minSdk 28`, `targetSdk 36`, `compileSdk 36`, `namespace com.nostrpost.app`, `applicationId com.nostrpost.app`.
- Manifest: `<uses-permission android:name="android.permission.INTERNET"/>` only, `<queries>` for nostrsigner, `MainActivity` exported launcher, `QuickPostWidgetReceiver`.
- `backup_rules.xml` + `data_extraction_rules.xml` exclude `datastore/`, secret file `nostr_secret.enc`, and `sharedpref`.
- Themes: Material3, edge-to-edge, dynamicColor support, light/dark.
- Gradle wrapper 9.3.1, `local.properties` with `sdk.dir`.
**Validation:** `./gradlew assembleDebug` succeeds → debug APK.

## Phase 3 — Composer
**Objective:** The entire app after setup.
**Implementation:**
- `MainActivity` hosting `NavHost` (Setup vs Composer vs Settings) or simple state switch based on `Preferences.isSetupComplete`.
- `ComposerScreen`: full-screen `BasicTextField` with `remember` + `FocusRequester`, `LaunchedEffect` requests focus + `WindowInsets.ime`, keyboard opens automatically (`WindowCompat.setDecorFitsSystemWindows(window,false)` + `showSoftInput`). Typography large, generous padding, edge-to-edge, no top app bar, single centered hint "What's on your mind?".
- Publish button: `FloatingActionButton` or bottom-anchored `Button` enabled iff `content.trim().isNotEmpty()` && `!publishing`.
- Back preserves draft via `onBackPressedDispatcher` + draft repo.
- Success UI: `CircularProgressIndicator` → checkmark (0.4s) → `finishAndRemoveTask()`.
- Failure UI: inline text `"Couldn't publish. Your post is still here."` + button becomes Retry.
- Accessibility labels, 48dp touch targets.
**Validation:** Launch → cursor in field, keyboard open, typing smooth, no jank, TalkBack reads field and Publish.

## Phase 4 — External signing
**Objective:** Production NIP-55 pipeline with least privilege.
**Implementation:**
- `SignerRepository` interface with `getPublicKey()` + `signEvent(unsignedJson): SignedEvent`. Two impls: `Nip55Signer` and `LocalSigner`.
- `Nip55Signer`:
  - detection via `PackageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")))`
  - `getPublicKey`: Intent `type=get_public_key`, extra `permissions=[{type:sign_event,kind:1}]`, use `registerForActivityResult` contract. On `RESULT_OK` read `result` (pubkey hex) + `package`. Store in DataStore. Cache package for later `intent.setPackage(...)`.
  - `signEvent`: try ContentResolver `content://<pkg>.SIGN_EVENT` with `selectionArgs=listOf(eventJson, "", pubkey)` → if `cursor!=null && moveToFirst && getColumnIndex("rejected")==-1` return signature+event. Else fall back to Intent `type=sign_event`, `putExtra("current_user", pubkey)`, data `nostrsigner:<eventJson>`. Parse `result`+`event`.
  - handle `rejected==true`, `resultCode!=RESULT_OK`, package gone.
- `SignerRepository` chooses active signer from DataStore.
- All signing off main thread, verify after signing.
**Validation:** Unit tests for detection logic, intent creation, parsing. Manual with Amber: setup → connect → permission remember → second publish uses ContentResolver (no app switch). Test cancel → "Signing cancelled." preserved draft.

## Phase 5 — Relay publishing
**Objective:** Concurrent broadcast, at least one OK = success.
**Implementation:**
- `EventFactory`: `createUnsigned(pubkey, content): Pair<UnsignedEvent, String canonicalJson>` — canonical JSON `[0, pubkeyHex, createdAtSec, 1, [], content]` with proper escaping (`\b\f\n\r\t\"\\` + control chars). Compute id `sha256(utf8(canonical))`.
- `NostrCrypto`: wrap `secp256k1-kmp` for `signSchnorr(privKey32, sha256)` + `verifySchnorr(pubKey32, msg32, sig64)` + `derivePubkey(priv)` + bech32 decode `nsec`→32 bytes. Provide hex utils. Schnorr impl via `Secp256k1` singleton after library init.
- `EventVerifier`: re-serialize, recompute id, verify signature matches pubkey, check kind==1, content byte-exact, tags `[]`.
- `RelayPublisher`: for each relay url create `OkHttpClient.newWebSocket(request, listener)` with 5s timeout, send `["EVENT", signedEventJsonObj]`, wait for `["OK", id, true/false, msg]`. Collect with `CompletableDeferred` + `withTimeout(6000)`. Success if any `true` and `okId == eventId`. Close sockets immediately after first success + cancel rest. Ensure no leak (close all on completion/cancel).
- `RelayConfig`: DataStore list defaults 4 relays, editable in Settings, validate `wss://` (allow `ws://` for debug but warn).
**Validation:** Unit tests with fixtures (known vec: NIP-01 id/sig), mock WebSocket server. Integration: publish hardcoded signed event to real relays, assert `["OK",…true]`.

## Phase 6 — Widget
**Objective:** One-tap shutter button.
**Implementation:**
- Jetpack Glance `QuickPostWidget: GlanceAppWidget` + `QuickPostWidgetReceiver: GlanceAppWidgetReceiver`.
- `widget_info.xml` appwidget-provider: `minWidth 40dp`, `minHeight 40dp`, `widgetCategory home_screen`, `resizeMode horizontal`, `previewImage`.
- Layout: `Box` rounded 24dp `GlanceTheme.colors.primaryContainer`, centered `Icon` (create `ic_edit` or `R.drawable.ic_compose`), 1×1 shows icon only, 2×1 variant via `SizeMode.Responsive` (`Small=icon`, `Medium=icon+ "Post to Nostr"` text) if not adding complexity. Use `actionStartActivity<MainActivity>(actionParametersOf(...))`.
- MainActivity handles `intent.getBooleanExtra("quick_compose",false)` → directly composer, skip setup animation.
- Theming: `GlanceTheme` with dynamic color on Android 12+, correct padding (`GlanceAppWidget` handles).
**Validation:** Widget appears in picker, renders light/dark, tap launches composer warm <300ms, survives reboot (RECEIVE_BOOT_COMPLETED not needed for Glance).

## Phase 7 — Draft persistence
**Objective:** Exactly one draft, never lost.
**Implementation:**
- `Preferences` via `DataStore<Preferences>` keys: `draft` (String), `isSetupComplete`, `signerMode` ("nip55"|"local"), `pubkeyHex`, `signerPackage`, `relays` (StringSet/json), `keyProtectionLevel`.
- `DraftRepository` debounces writes 300ms (`snapshotFlow` + `debounce`), writes on `onPause` + `onStop` immediately. On launch restore draft into `ComposerViewModel.state`. On success `clearDraft()`. On `onBack` no dialog.
- Process death: DataStore survives; ViewModel restores from disk in `init`.
**Validation:** Unit tests: first char persists, rotation restores, Back preserves, cancel preserves, success clears.

## Phase 8 — Local key fallback
**Objective:** Secure fallback, no deprecated crypto.
**Implementation:**
- `AndroidKeystoreSecretStore`:
  - Key alias `nostr_wrap_key` (AES-256-GCM). `KeyGenerator.getInstance("AES","AndroidKeyStore")` + `KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT|DECRYPT).setBlockModes(GCM).setEncryptionPaddings(NONE).setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false)` (spec says default conservative, but allow device-unlocked fast mode; do NOT set `setUserAuthenticationValidityDurationSeconds` unless biometric mode explicitly added — we default to no auth per spec "Fast mode" to avoid double auth). Try `setIsStrongBoxBacked(true)` in try/catch; on `StrongBoxUnavailableException` retry without. Record protection level in prefs.
  - Encrypt: `Cipher.getInstance("AES/GCM/NoPadding")` init ENCRYPT with keystore key, get `iv` 12 bytes, `doFinal(secret32)`, store `iv+ciphertext+tag` as file `nostr_secret.enc` in `filesDir` (private). Also store `protectionLevel` string.
  - Decrypt: read file, split iv(12), ciphertext, `DEC` mode with `GCMParameterSpec(128, iv)`.
  - Clear: overwrite secret array with 0 after sign.
  - Backup exclusion: `backup_rules.xml` excludes `file/nostr_secret.enc`; `data_extraction_rules.xml` excludes `file/` + `datastore/`.
- `LocalSigner`: decrypt only inside `signEvent`, `MessageDigest.sha256(canonicalJson)`, `Secp256k1.signSchnorr(priv, hash)` with aux randomness, clear `priv` bytes via `Arrays.fill(0)`.
- Import screen: `SecureImportScreen` with `FLAG_SECURE` (`window.setFlags(FLAG_SECURE)`), password `TextField` with `VisualTransformation`, accepts `nsec1...` (bech32 decode, verify 5-bit → 8-bit, hrp "nsec", checksum) or 64-char hex. Validate 32 bytes, derive pubkey, verify pubkey length 32 hex, encrypt, clear field, navigate to composer. Never log secret. Copy-paste handling clears input after success.
**Validation:** Tests: nsec/hex valid/invalid, pubkey derivation matches vector, encrypt roundtrip, StrongBox fallback, corrupted ciphertext handling, Keystore key missing → error "Key unavailable, re-import".

## Phase 9 — Settings
**Objective:** Tiny settings, no navigation hierarchy.
**Implementation:**
- `SettingsScreen` accessible via small gear icon top-end in Composer (24dp, `alpha 0.4`, contentDescription "Settings").
- Sections:
  - Identity: if nip55 show `npub` truncated (`npub1abc…xyz`) + signer package + `[Change signer]` (reset prefs, go to Setup). If local show `npub truncated` + `Protection: StrongBox/Hardware-backed` + `[Remove key]` (delete file + clear DataStore, confirm dialog). Copy `npub` button ( ClipboardManager ), never copy nsec.
  - Relays: LazyColumn of `wss://` URLs + Add (dialog validates wss), Remove, Reset defaults.
  - About: versionName, versionCode.
- No other settings.
**Validation:** Manual toggle relays, persistence across restart. Accessibility labels.

## Phase 10 — Tests
**Objective:** Confidence without over-engineering.
**Implementation:**
- Unit tests (`src/test`):
  - `NostrCryptoTest`: canonical serialization vector, event id, schnorr sign/verify, modified content fails verify.
  - `Bech32Test`: nsec→hex, npub truncated display, invalid checksum rejected.
  - `SecretStoreTest` (Robolectric): encrypt/decrypt roundtrip, corrupted tag fails, fallback.
  - `RelayPublisherTest`: mock OkHttp WebSocket via `MockWebServer` or fake listener, test OK true/false, timeout, malformed, partial success.
  - `DraftRepositoryTest`: debounced persist, rotation, preserve on failure.
  - `PreferencesTest`
- Instrumentation tests (`androidTest`): Compose rule for Composer focus/keyboard, widget tap intent.
- Lint: `./gradlew lint` should pass or justify remaining.
**Validation:** `./gradlew test` passes, `./gradlew lint`.

## Phase 11 — Security review
**Objective:** Audit spec threat model.
**Implementation:**
- Grep repo for `nsec`, `private`, `secret`, `log` — ensure no `Log.d` contains raw keys, no `System.out`, no crash handler logs secret. Review all `Log` tags.
- Manifest permission audit — only `INTERNET`.
- Check `FLAG_SECURE` on import screen only, not composer.
- Check backup rules exclude secret.
- Check secret never in ViewModel state / Compose state / savedInstanceState; only local var inside `LocalSigner.sign` and cleared.
- Check verification before publish.
- Document limitations in SECURITY.md.
**Validation:** Manual grep, `adb shell run-as com.nostrpost.app ls files/`, check `nostr_secret.enc` not in `adb backup`? Validate `backup_rules.xml` by inspecting merged manifest.

## Phase 12 — UX/performance pass
**Objective:** Feels like system utility.
**Implementation:**
- Measure warm/cold launch via `adb shell am start -W`. Target warm <300ms. Optimize: no DB init, no relay connect, no signer handshake before first frame; init signer async. Remove splash delay (`windowSplashScreenBackground` transparent, `postSplashScreenTheme`).
- Remove any logo animation, drawer, bottom nav, cards. Edge-to-edge check on Pixel, dark/light, dynamic color contrast check.
- Polish Publish button: `enabled` alpha, prevent double tap (disable during `publishing`), small progress → checkmark 400ms → finish.
- Polish widget: ensure rounded 24dp, icon scale 24dp→32dp, padding 4dp, light/dark preview correct.
- Font scaling test (Settings → large font), TalkBack.
**Validation:** Manual UX review on emulator Pixel 7, API 36.

## Phase 13 — Release build
**Objective:** Produce usable APK.
**Implementation:**
- `build.gradle.kts` signingConfig debug uses `~/.android/debug.keystore` (auto). Release via `assembleRelease` unsigned (document `apksigner` step). No Firebase.
- README include exact Gradle cmds: `./gradlew assembleDebug`, `./gradlew assembleRelease`, `./gradlew test`, `./gradlew lint`, `./gradlew installDebug`.
- ARCHITECTURE.md / SECURITY.md concise.
- Final APK at `app/build/outputs/apk/debug/app-debug.apk` and `release/app-release-unsigned.apk`.
**Validation:** `./gradlew clean && ./gradlew test && ./gradlew lint && ./gradlew assembleDebug` then `adb install -r app/build/...debug.apk`, repeat canonical acceptance test.

---

## Definition of Done mapping
- All spec items 94 covered. If any item fails, iterate before declaring done.
- Canonical acceptance test: widget tap → type `"Social media became worse when publishing became an invitation to consume."` → Publish → Amber signs (no nsec in our app) → relay OK → checkmark → `finishAndRemoveTask()` → home, no feed, no notifications.

