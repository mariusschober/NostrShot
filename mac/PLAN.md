# NostrBar — Plan

Minimal macOS menu-bar composer for publishing Nostr notes.
Goal metric: **time from intention to published kind-1 event**.

## Phase 0 — Environment & research ✅ DONE

- Environment: macOS 26.5.2, Xcode 26.6, Swift 6.3.3 (arm64).
- Verified NIP-01 (canonical serialization, ID, wire format, `["EVENT"]/["OK"]`) and NIP-19 (bech32 `npub`/`nsec`, test vectors) from official specs.
- Crypto dependency survey: older Swift secp256k1 packages (GreenAddress, bitmark-inc, Sjors) are gone/unmaintained. `21-DOT-DEV/swift-secp256k1` is the surviving actively-maintained Swift package (BIP340 vectors tested in-repo). Chose it, pinned `0.23.2`, product `P256K`.
- Shortcut: `sindresorhus/KeyboardShortcuts` `3.0.1` (Carbon `RegisterEventHotKey` internally; no Accessibility permission; sandbox-compatible).
- Relays: candidates verified live at build time via integration test; defaults isolated in `DefaultRelays.swift`.

## Phase 1 — ✅ DONE — Project shell

Swift Package (`swift-tools-version: 6.1`, platform macOS 14+) with executable target `NostrBar` + test target `NostrBarTests`. `scripts/build_app.sh` assembles `build/NostrBar.app` (Info.plist with `LSUIElement`, ad-hoc codesigned). Rationale documented in ARCHITECTURE.md.

Verification: `swift build` succeeds; bundled app launches with no Dock icon.

## Phase 2 — ✅ DONE — Nostr primitives (pure logic)

- `Nostr/Bech32.swift`: bech32 (not-m) encode/decode, 8↔5-bit regrouping.
- `Nostr/Keys.swift`: `nsec…` / 64-hex parsing, x-only pubkey derivation, `npub` encoding.
- `Nostr/NostrEvent.swift`: canonical NIP-01 serialization (manual escaping per spec), ID = sha256, Schnorr signing via `P256K`, wire JSON.

Verification: unit tests vs NIP-19 vectors, canonical-string assertions, BIP340 test vector #0, self-consistent sign→verify round trip.

## Phase 3 — ✅ DONE — Relay pool

- `Nostr/RelayConnection.swift`: actor per relay. States disconnected→connecting→connected, exponential backoff (cap 60s), 30s keepalive pings while connected, pending-publish map keyed by event id with hard deadlines (continuations always resumed exactly once).
- `Nostr/RelayPool.swift`: actor coordinating concurrent publish; success = ≥1 positive `OK`; losers keep broadcasting in background.
- Network wake/reconnect via `NWPathMonitor` + workspace wake notifications.

Verification: message encode/parse tests; live integration test (env-gated) publishing a disposable-key event to a public relay and asserting `OK true`.

## Phase 4 — ✅ DONE — Keychain

- `Storage/KeychainStore.swift`: generic password get/set/delete, `WhenUnlockedThisDeviceOnly`.
- Private key only ever persisted there; never logged; `SecureField` on import; npub shown post-import.

Verification: keychain store round-trip/delete tests; grep audit for secret leakage.

## Phase 5 — ✅ DONE — Menu bar

`MenuBarController`: template SF Symbol status item; menu = New Post (with live shortcut glyph), Settings…, Quit NostrBar. No Dock icon (`LSUIElement` + `.accessory` policy).

## Phase 6 — ✅ DONE — Composer

`ComposerPanel` (NSPanel, floating level, joins all spaces + fullscreen auxiliary, transparent full-size-content titlebar, vibrancy background, clear bg + shadow) hosting `ComposerView` (SwiftUI TextEditor + footer). Local `NSEvent` monitor: ⌘Return publish, Escape close (draft preserved). Opens centered on screen containing mouse. Re-invoking shortcut focuses existing panel. ResignKey hides panel, preserving draft.

## Phase 7 — ✅ DONE — Settings & setup

Single small form: Account (truncated npub, Replace Private Key…), Shortcut recorder, Relay list (add/remove/status dot), Launch at Login (`SMAppService`). First-launch `SetupView` imports/validates key, confirms derived npub, no other fields.

## Phase 8 — ✅ DONE — Tests

Bech32 vectors, key parsing/derivation, event serialization/ID/signature, relay message codec, draft persistence semantics, keychain round-trip, env-gated live relay test.

## Phase 9 — ✅ DONE — Integration

Wire phases 2–7 end-to-end: launch → warm relays → hotkey → compose → ⌘Return → `OK`. Failure UX keeps text; Retry rebuilds event.

## Phase 10 — ✅ DONE — UX/security/performance polish

Manual matrix from spec §40 (shortcut from Finder, Escape/draft, dark/light, multi-screen, invalid relay). Security pass: grep for `nsec`/logging/UserDefaults secrets. Simplification pass: delete anything not serving the five core behaviors.

## Phase 11 — ✅ DONE — Final build

Release build → `.app` → re-run acceptance checklist → clean repo state.

---

## Key decisions log

| Decision | Choice | Why |
|---|---|---|
| Project format | SPM + bundling script | Fully CLI-buildable/testable; hand-written pbxproj is fragile |
| Crypto | `swift-secp256k1` (P256K) | Only maintained Swift secp256k1; BIP340-tested; CryptoKit-style API |
| Protocol layer | Custom ~400 lines | App uses a sliver of Nostr; smaller/auditable than SDK |
| WebSockets | `URLSessionWebSocketTask` | Native, no deps |
| Serialization | Manual string builder | NIP-01 forbids JSON libs' `\uXXXX` escaping |
| Publish ack | Wait for first `OK` (≤5s deadline) | Warm connections answer fast; protects text from false success |

---

## Completion log (final)

- All 37 unit tests pass; live relay integration test accepted a real kind-1 event (`NOSTRBAR_LIVE=1`).
- User-verified end-to-end: first real post published to Nostr via ⌃⌥N → type → ⌘↩.
- Late fixes during integration: synchronous hotkey path (Keychain moved off the critical path), `orderFrontRegardless` fallback, stale-session cleanup in reconnect, text-loss race during in-flight publish, composer editor rewritten as direct NSTextView bridge for exact vertical balance + placeholder alignment (user request).
- Security pass: no prints/logs of key material; Keychain-only persistence; UserDefaults holds only relays + draft.
