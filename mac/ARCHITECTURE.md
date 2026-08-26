# NostrBar — Architecture

## Goal

Minimize **time from intention to published Nostr event**. Every choice below serves that: the app stays resident, relay connections stay warm, the composer appears instantly with focus, and publishing is one keystroke.

## Shape

```text
Sources/NostrBar/
├── Main.swift                  entry point (NSApplication, accessory policy)
├── AppDelegate.swift           lifecycle, wiring, network monitoring
├── AppServices.swift           shared instances (preferences, keys, relays)
├── AppConstants.swift          product name/bundle/defaults — rename here
├── Nostr/
│   ├── Bech32.swift            bech32 (NIP-19 encoding substrate)
│   ├── Keys.swift              nsec/hex parsing, pubkey derivation
│   ├── NostrEvent.swift        canonical NIP-01 serialization, ID, signing
│   ├── RelayMessages.swift     ["OK"]/["NOTICE"] parsing
│   ├── RelayConnection.swift   one WebSocket + reconnect/backoff per relay
│   └── RelayPool.swift         concurrent publish, first-OK-wins
├── Storage/
│   ├── KeychainStore.swift     generic-password wrapper
│   ├── KeyManager.swift        identity load/import/sign (actor)
│   └── Preferences.swift       relays + draft (UserDefaults)
└── UI/
    ├── MenuBarController.swift status item + menu
    ├── ComposerController.swift panel lifecycle, publish pipeline
    ├── ComposerPanel.swift      NSPanel (floating, joins all spaces)
    ├── ComposerView.swift       SwiftUI chrome (footer, background)
    ├── ComposerTextView.swift   AppKit NSTextView bridge (the editor)
    ├── SettingsView.swift       account / shortcut / relays / general
    ├── SetupView.swift          first-run key import
    └── WindowControllers.swift small NSWindow hosts
```

## Decisions

### Custom thin protocol layer (Approach B) + one crypto dependency
The app needs a sliver of Nostr: kind-1 events, `["EVENT"]`, `["OK"]`, bech32 keys. A full SDK would pull far more surface than the product uses. The protocol layer is ~400 auditable lines. Cryptography is **not** hand-rolled: `swift-secp256k1` (P256K product) provides BIP-340 Schnorr via libsecp256k1, verified in our tests against official BIP-340 vectors. It is the only actively maintained Swift secp256k1 package (the older GreenAddress/bitmark/Sjors packages are gone).

### SwiftPM + bundle script instead of .xcodeproj
Everything is buildable and testable from the CLI (`swift build`, `swift test`); `scripts/build_app.sh` assembles and ad-hoc-signs `build/NostrBar.app` with `LSUIElement`. A hand-maintained pbxproj would add fragility without benefit at this size.

### Manual canonical serialization
NIP-01 requires specific escaping (`\n`, `\"`, `\\`, `\r`, `\t`, `\b`, `\f`) and **verbatim** UTF-8 for everything else. `JSONSerialization` escapes non-ASCII (`\uXXXX`), which would change event IDs. `NostrEventFactory.escape` implements exactly the spec.

### Signing signs the raw event id
BIP-340 Schnorr signs the 32-byte event id itself (not a re-hash). Caught by tests against official vectors after an initial double-hash bug.

### Relay model
One `RelayConnection` actor per relay: disconnected → connecting → connected, exponential backoff (1s→60s cap), 30s keepalive pings, reconnect on network-return and wake-from-sleep (`NWPathMonitor` + `didWakeNotification`). Session callbacks carry an identity token so the invalidated previous session's asynchronous `didCompleteWithError` can never tear down the fresh connection (a subtle reconnect-storm bug). `RelayPool.publish` sends the same signed event to every relay concurrently through a stream that is guaranteed to terminate (countdown box finishes it after the last result); the first positive `OK` resolves success while remaining relays keep broadcasting. Per-publish continuations always resume exactly once (OK / disconnect / deadline).

### Editor ownership
The `NSTextView` is the source of truth while the user types: `didChangeText` reports upward into the binding and records the reported value; SwiftUI updates only write into the view when the change came from outside (dictation, prefill, draft restore). Writing renders back over newer keystrokes would eat input and reset the caret — a failure mode the ownership rule eliminates by construction.

### Optimistic close with guaranteed restoration
⌘↩ hides the panel immediately; the note is signed and dispatched in the background. The body is held in `pendingNotes` until a relay accepts. Total failure (offline fail-fast via `NetworkMonitor`, zero relay `OK`s, or signing error) plays a muted tone, reopens the composer with the note restored — merged below anything the user typed meanwhile (`DraftRestore.merge`, unit-tested) — and shows Retry. Concurrent publishes are supported: rapid-fire notes each resolve independently.

### On-device dictation
A dedicated shortcut (⌃⌥D) opens the composer listening: Apple's `SFSpeechRecognizer` with `requiresOnDeviceRecognition` streams the transcript into the editor. The same key stops, ⌘↩ stops-and-publishes, Esc stops-and-closes. Microphone + speech permissions are requested on first use; nothing is visible in the normal typing interface. The NVIDIA ASR models (Canary/Nemotron) were assessed and deferred: they are NeMo/PyTorch models with no native Swift runtime — a CoreML conversion project — while Apple's stack is native, private, streaming, and free of app-size cost.

### Selection capture
⌃⌥⇧N reads the frontmost app's selected text via the Accessibility API (`kAXSelectedTextAttribute`) *before* activating NostrBar, then opens the composer pre-filled. This is the only feature requiring the Accessibility permission; the first press triggers Apple's standard grant dialog.

### Feel
Open: 120 ms fade + 0.98→1.0 scale; close: 90 ms fade (both skipped under Reduce Motion). Success/failure ticks are synthesized PCM (no assets). The menu-bar icon switches to `exclamationmark.bubble` while *every* configured relay is disconnected (event-driven from relay state changes, no polling). "Copy Last Note Link" in the menu writes the NIP-19 `nevent1…` reference of the latest accepted note.

### Keychain at launch is asynchronous
Keychain validation of an ad-hoc-re-signed binary can take seconds. The composer therefore opens synchronously on the hotkey (inside the user-interaction window macOS grants focus in), while identity loads in a background task. Signing happens at publish time; if no identity exists then, the setup window opens.

### AppKit where it matters, SwiftUI where it's cheaper
The composer is an `NSPanel` (floating level, joins all spaces, fullscreen-auxiliary, transparent full-size-content titlebar) hosting SwiftUI. The editor is a direct `NSTextView` bridge — exact inset control, vertically balanced short text (Spotlight-style; re-centered in `draw`/`setFrameSize` since `layout()` never fires in non-autolayout hierarchies), native undo/redo/copy/paste, and a placeholder drawn inside the view so it always matches the text origin. The caret location persists with the draft and is restored on reopen. ⌘Return/Escape are handled by a local `NSEvent` monitor installed while the panel is key.

### Global shortcut
`KeyboardShortcuts` (Sindre Sorhus) — Carbon `RegisterEventHotKey` under the hood: global, no Accessibility permission, sandbox-compatible, negligible idle cost, ships a native recorder for Settings.

## Testing

`swift test` covers: bech32 + NIP-19 vectors, key parsing/derivation, canonical serialization, event id vs independent SHA-256, BIP-340 signature vectors, sign→verify round trips, relay message codec, relay URL normalization, draft semantics, Keychain round-trips, KeyManager import/relaunch. The live relay publish test is env-gated: `NOSTRBAR_LIVE=1 swift test --filter LiveRelayIntegrationTests`.

## Development hooks

`NOSTRBAR_DEBUG_COMPOSER=1 build/NostrBar.app/Contents/MacOS/NostrBar` opens the composer at launch (used for automated UI inspection; harmless in production).
