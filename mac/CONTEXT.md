# CONTEXT.md — Project Handoff

Everything an engineer or agent needs to continue NostrBar without re-deriving its history. Read this before changing anything.

---

## 1. Identity

- **Product**: NostrBar — a native macOS menu-bar composer that publishes short text notes to Nostr. The entire product is: global shortcut → type → `⌘↩` → signed kind-1 event broadcast to relays.
- **Working directory**: `/Users/schober/Projects/NostrShot`
- **Bundle ID**: `com.nostrbar.app` · **Version**: `1.0.0` (single source: `AppConstants.version`)
- **Product name is isolated** in `Sources/NostrBar/AppConstants.swift` (name, bundle id, keychain service, default relays). Renaming = edit that file + re-run `scripts/build_app.sh`.
- **Not** a git repo yet (as of this writing). Initialize before publishing; `.gitignore` already covers `.build/`, `build/`, `.DS_Store`, `*.app`, `*.xcodeproj`.

## 2. Current status (verified working)

All of the following were implemented AND verified on this machine:

- ✅ Builds clean (zero warnings), 42/42 unit tests pass
- ✅ Live end-to-end publish accepted by a public relay (`NOSTRBAR_LIVE=1 swift test --filter LiveRelayIntegrationTests`)
- ✅ User published real notes through the app
- ✅ Menu-bar app: no Dock icon, no main window; left-click icon → composer, right-click → menu
- ✅ Global shortcuts: `⌃⌥N` composer, `⌃⌥⇧N` selection capture, `⌃⌥D` dictation (all re-recordable in Settings)
- ✅ Optimistic publish: instant close, background broadcast, first-`OK` success, failure restores text (merged below newer typing), rapid-fire notes
- ✅ Draft + caret persistence across close/quit/failure
- ✅ On-device dictation (Apple Speech), crash-fixed (see §7)
- ✅ Relay pool: 4 relays connect <1s, zero churn, backoff reconnect, wake/network recovery, icon outage signal
- ✅ Sounds (synthesized, no assets), open/close micro-animation (Reduce-Motion aware), app icon generated at build time
- ✅ `.app` and drag-to-Applications `.dmg` packaging scripts

**Known limitations (accepted for v1):** no offline queue, no editing of past notes, no NIP-07, no multi-account, ad-hoc signing only (notarization steps documented in README), dictation quality bound to Apple's recognizer (NVIDIA ASR models assessed and deferred — see ARCHITECTURE.md).

## 3. Infrastructure & toolchain

| Component | Value |
|---|---|
| Host | macOS 26.5 (arm64, M1 Pro), Xcode 26.6, Swift 6.3.3 |
| Target | macOS 14+ (`platforms: [.macOS(.v14)]`) |
| Build system | Swift Package Manager (`swift-tools-version: 6.1`), **Swift 6 language mode** (strict concurrency ON) |
| Project format | SPM executable target + test target. **No .xcodeproj** — deliberate (CLI-driven lifecycle). `.app` assembled by script |
| Dependencies | `sindresorhus/KeyboardShortcuts` **3.0.1** (exact pin) · `21-DOT-DEV/swift-secp256k1` **0.23.2** (exact pin, product `P256K`) |
| Signing | Ad-hoc (`codesign --sign -`). Keychain ACL validation of re-signed binaries can take seconds at first access — identity load is deliberately async |
| Packaging | `scripts/build_app.sh` (Info.plist w/ `LSUIElement`, mic+speech usage strings, generated icns) → `build/NostrBar.app`; `scripts/make_dmg.sh` → drag-to-Applications dmg |

**Commands**

```sh
swift build                # fast check
swift test                 # unit suite (42)
NOSTRBAR_LIVE=1 swift test --filter LiveRelayIntegrationTests   # network test, manual only
./scripts/build_app.sh     # release .app
./scripts/make_dmg.sh      # release .app + .dmg
```

**Dev hooks** (documented, harmless in production):
- `NOSTRBAR_DEBUG_COMPOSER=1 build/NostrBar.app/Contents/MacOS/NostrBar` — open composer at launch (for automated screenshots/inspection)
- Window inspection: CGWindowList scripts (owner `NostrBar`, composer is layer 3); `screencapture -l <windowID>` captures the composer cross-Space

## 4. Layout map

```
Sources/NostrBar/
  Main.swift               @main, NSApplication, .accessory policy
  AppDelegate.swift        lifecycle, hotkey wiring, network/wake observers, setup flow
  AppServices.swift        shared instances: Preferences, KeyManager, RelayPool, NetworkMonitor, lastPublishedEvent
  AppConstants.swift       name/bundle/relays/version + KeyboardShortcuts.Name definitions
  Nostr/
    Bech32.swift           bech32 (not-m) codec
    Keys.swift             nsec/hex parsing, x-only pubkey derivation, npub/nsec, hex helpers
    Nip19.swift            nevent TLV encode/decode
    NostrEvent.swift       canonical serialization, event id, signing, wire JSON, self-verification
    RelayMessages.swift    ["OK"]/["NOTICE"] parsing
    RelayConnection.swift  actor per relay: WS lifecycle, backoff, pings, per-publish continuations
    RelayPool.swift        actor: set reconcile, concurrent publish (first-OK-wins), health callback
  Storage/
    KeychainStore.swift    generic-password wrapper
    KeyManager.swift       actor: load/import/sign; key only in Keychain
    NetworkMonitor.swift   NWPathMonitor wrapper + onBecameSatisfied
    Preferences.swift      UserDefaults: relays, draft, draftCursor, playSound
  UI/
    MenuBarController.swift  status item (left=compose, right=menu), icon health, menu items
    ComposerController.swift @MainActor ObservableObject: visibility, animation, optimistic publish, dictation glue
    ComposerPanel.swift      NSPanel subclass (floating, all-spaces, fullscreen-auxiliary)
    ComposerView.swift       SwiftUI chrome: editor + footer (listening/failed states)
    ComposerTextView.swift   NSViewRepresentable around NSTextView + ownership rule
    Dictation.swift          SFSpeechRecognizer on-device streaming
    SelectionCapture.swift   AX selected-text capture
    SettingsView.swift / SetupView.swift / WindowControllers.swift
    Sound.swift              synthesized WAV blips (no assets)
Tests/NostrBarTests/       8 files, 42 tests (vectors, codec, semantics, keychain, live-gated)
scripts/                   build_app.sh, make_dmg.sh, make_icon.swift
```

## 5. Non-negotiable invariants (breaking these = regressions we already paid for)

1. **Outbound Nostr JSON is hand-serialized.** NIP-01 requires verbatim UTF-8 with only 7 escapes; `JSONSerialization` emits `\uXXXX` and would change event IDs. Use `NostrEventFactory.escape` / `serializedForID` / `relayMessage`. Inbound parsing may use JSONSerialization.
2. **BIP-340 signs the raw 32-byte event id** — never re-hash it. The P256K API takes a `HashDigest` wrapper (`SHA256Digest(Array(idBytes))`); it wraps bytes, it does not hash them. Official BIP-340 vectors in `EventTests` guard this.
3. **HashDigest byte extraction**: use `digest.withUnsafeBytes { Array($0) }` (public instance method). `.bytes` is internal and silently resolves to a protocol default — produces truncated ids.
4. **Editor ownership rule** (`ComposerTextView.swift`): while the user types, the NSTextView is the source of truth. `onTextChange` only reports upward (`coordinator.lastReportedText = value; text = value`) and must **never** write `textView.string`. `updateNSView` writes into the view only when `text != lastReportedText` (external change: dictation, prefill, restore, clear). Violations eat keystrokes and reset the caret.
5. **Every framework callback that leaves the process gets `@Sendable`** (Speech/TCC/AVFoundation call back on private queues). A closure inheriting `@MainActor` from its enclosing type traps at runtime (`dispatch_assert_queue_fail`) — this crashed dictation. Hop back with `Task { @MainActor in … }`.
6. **URLSession session-identity tokens** (`RelayConnection.SessionBox`): invalidating an old session fires `didCompleteWithError` asynchronously; without the token the stale callback tears down the fresh connection → infinite reconnect churn.
7. **AsyncStreams must finish.** A result stream consumed by `for await` that never gets `continuation.finish()` hangs the caller forever (this silently ate failed publishes). `RelayPool.publish` finishes via a countdown box.
8. **`NSView.layout()` never fires** in this non-autolayout hierarchy. Geometry-dependent work (width tracking, vertical re-centering) lives in `ComposerTextView.draw(_:)` and `setFrameSize(_:)`.
9. **The hotkey path must stay synchronous** through `composer.showOrFocus()`. macOS grants focus-stealing only within the user-interaction window; awaiting anything (Keychain!) before showing loses the window. Identity loads in background; signing happens at publish time.
10. **Keychain service** is `AppConstants.keychainService` (`com.nostrbar.app`), account `nostr-private-key`, accessibility `WhenUnlockedThisDeviceOnly`. Never move it to UserDefaults. Never log key material (audit: only relay hosts, event ids, states are logged).
11. **Composer sizing**: root SwiftUI view pins `.frame(width:height:)` from `AppConstants.composerSize`; the hosting view otherwise adopts SwiftUI's ideal size (observed 500×500 / 63pt-tall editor bugs).
12. **Swift 6 mode is on.** Global mutable state, non-Sendable captures, and inherited isolation are hard errors. PreferenceKey `defaultValue` must be `static let`.

## 6. Behavioral contracts (tested)

- Failed publish → text restored via `DraftRestore.merge`: empty editor gets the note; non-empty editor gets `pending + "\n\n" + current` (user's newer typing is never destroyed).
- Escape always preserves the draft; successful publish clears draft + cursor.
- Publish deadlines: connect-wait ≤1.5s per relay, `OK` wait ≤3s; offline (`NetworkMonitor.isSatisfied == false`) fails instantly with "You're offline…".
- Success = ≥1 relay `OK true`. Other relays keep broadcasting after early return.
- Panel: re-invoking the hotkey while visible focuses instead of toggling; resignKey hides (draft kept); ⌘Return/Escape handled by a local `NSEvent` monitor gated on `panel.isKeyWindow`.

## 7. Post-mortems (already fixed — do not reintroduce)

- **Dictation SIGTRAP**: `SFSpeechRecognizer.requestAuthorization` handler inherited MainActor → TCC queue trap. Fixed via `@Sendable` closures (invariant 5).
- **Silent publish hangs**: unfinished AsyncStream (invariant 7).
- **Relay churn**: stale-session teardown (invariant 6).
- **Caret-to-zero on every keystroke**: `textView.string = value` echo in `onTextChange` (invariant 4).
- **8s composer delay**: Keychain read on the hotkey path (invariant 9).
- **Double-hashed signatures**: caught by BIP-340 vectors (invariant 2).
- **Truncated event ids**: `HashDigest.bytes` (invariant 3).

## 8. Conventions

- Swift 6 strict concurrency; `@MainActor` for all UI + services; actors for key/relay domains; no `DispatchSemaphore` bridging.
- Comments only where they encode non-obvious rationale (several exist as guardrails — keep them when refactoring).
- No new dependencies without extraordinary justification (current two are load-bearing).
- Logs: `os.Logger`, subsystem `app.nostrbar`, categories `relay`, `relay-pool`, `app`, `composer`. Debug level; never secrets.
- Tests: Swift Testing (`@Test`), vectors preferred over mocks; live network test env-gated.

## 9. Ideas parked (from the product review — none committed)

- Remaining wow-tier: ⌘C-during-visible-failure copies `nevent` (menu item already covers copy), per-relay status dots in Settings (snapshot exists), About section.
- CoreML port of a small open ASR model if Apple dictation quality proves insufficient (assessed: NeMo/PyTorch models are not natively runnable; do not bundle Python).
- Notarized Developer-ID distribution flow.

## 10. Quick orientation for an agent

1. Read `ARCHITECTURE.md` for decisions, this file for state and guardrails.
2. Run `swift build && swift test` — both must be green before and after any change.
3. For UI verification: launch with `NOSTRBAR_DEBUG_COMPOSER=1`, capture via `screencapture -l <windowID>` (find window ID via CGWindowList, owner `NostrBar`, layer 3). Display must be awake.
4. For relay verification: stream `log stream --predicate 'subsystem == "app.nostrbar"' --level debug` while launching; expect 4× "connected", 0× "disconnected" in a quiet window.
5. For publish verification: `NOSTRBAR_LIVE=1 swift test --filter LiveRelayIntegrationTests`.
