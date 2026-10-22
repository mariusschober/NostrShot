# NostrBar

<p align="center">
<strong>A native macOS menu-bar composer for Nostr.</strong><br>
Press a shortcut from anywhere → type → <code>⌘↩</code> → published.
</p>

---

## What it is

NostrBar is not a Nostr client. There is no feed, no timeline, no profile, no replies — by design. It is a single-purpose utility that collapses the distance between *having a thought* and *having it on Nostr* to a few keystrokes:

> thought → `⌃⌥N` → words → `⌘↩` → gone.

The app lives quietly in the menu bar (no Dock icon, no window). Publishing is optimistic: the composer disappears the instant you commit, the note is signed locally (BIP-340 Schnorr) and broadcast to your relays concurrently, and the first relay acceptance confirms success with a quiet tick. If no relay accepts, your text is restored to the composer untouched, with a Retry button.

**Design metric:** time from intention to published event. Everything else is subordinate.

## Features

- **Global composer** — `⌃⌥N` from any app, fullscreen included; opens centered on the screen your mouse is on, focused, instant
- **Optimistic publishing** — instant close, background broadcast, first-`OK`-wins, guaranteed text restoration on failure, rapid-fire notes supported
- **Selection capture** — `⌃⌥⇧N` pulls the selected text of the frontmost app straight into the composer
- **On-device dictation** — `⌃⌥D` streams Apple speech recognition (never leaves the Mac) into the composer
- **Draft that survives everything** — text and caret position persist across closes, quits, and failed publishes
- **Warm relay pool** — 4 default relays, concurrent broadcast, automatic reconnect with backoff, wake/network awareness, icon signals total outage
- **Keychain-held identity** — `nsec`/hex import, validated, never displayed or logged again
- **Small settings surface** — shortcuts, relays, launch-at-login, sound toggle. Nothing else.
- **Native** — Swift 6, AppKit + SwiftUI, Swift Package Manager, ~2 MB app, no telemetry, no backend

## Build

Requirements: macOS 14+, Xcode 15+ (built with Xcode 26 / Swift 6.3).

```sh
git clone <repo> && cd NostrShot
swift build                 # debug build
./scripts/build_app.sh      # release build → build/NostrBar.app (ad-hoc signed)
./scripts/make_dmg.sh       # → build/NostrBar.dmg (drag-to-Applications image)
```

## Test

```sh
swift test                                              # 42 unit tests
NOSTRBAR_LIVE=1 swift test --filter LiveRelayIntegrationTests   # live publish to a public relay
```

The suite covers NIP-19 bech32 vectors, official BIP-340 Schnorr signature vectors, canonical NIP-01 serialization, event-ID cross-checks against an independent SHA-256, relay message codec, draft/cursor semantics, Keychain round-trips, and the publish-restoration merge rule.

## Run

```sh
open build/NostrBar.app
```

First launch shows a tiny setup window: paste your private key (`nsec1…` or 64 hex chars). It is validated, stored in the macOS Keychain, and never shown again — you confirm via the derived `npub…`.

## Keyboard

| Shortcut | Action |
|---|---|
| `⌃⌥N` | Open composer |
| `⌃⌥⇧N` | Capture frontmost app's selected text into composer |
| `⌃⌥D` | Start/stop dictation (on-device) |
| `⌘↩` | Publish (composer closes instantly) |
| `Esc` | Close — draft and caret are kept |

All shortcuts are user-configurable in Settings. Menu bar: **left-click** opens a new post, **right-click / control-click** opens the menu (New Post, Capture Selection, Dictate, Copy Last Note Link — known broken, see backlog NOSTRSHOT-14 — Settings…, Quit).

## Security

- The private key exists only in the macOS Keychain (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`); it is never logged, echoed, or transmitted
- Signing is local BIP-340 Schnorr via libsecp256k1 (verified against official test vectors)
- Dictation uses Apple's on-device recognizer; audio never leaves the Mac and is only active while you are dictating
- No analytics, no telemetry, no accounts, no backend — the only network traffic is your configured relay WebSockets
- Drafts (plain text) and relay URLs live in UserDefaults; they contain no secrets
- Selection capture is the only feature requiring the Accessibility permission, and only after you use it

## Architecture

Swift Package with an executable target; `scripts/build_app.sh` assembles the `.app` (Info.plist with `LSUIElement`, generated `.icns`, ad-hoc codesign).

```
Sources/NostrBar/
├── Main.swift / AppDelegate.swift / AppServices.swift / AppConstants.swift
├── Nostr/        Bech32, Keys, Nip19, NostrEvent, RelayConnection, RelayMessages, RelayPool
├── Storage/      KeychainStore, KeyManager, NetworkMonitor, Preferences
└── UI/           MenuBarController, ComposerController, ComposerPanel,
                  ComposerView, ComposerTextView, Dictation, SelectionCapture,
                  SettingsView, SetupView, Sound, VisualEffectBackground, WindowControllers
```

Design decisions and the reasoning behind them (custom thin protocol layer vs SDK, manual canonical serialization, optimistic publish semantics, editor ownership rules) are documented in [ARCHITECTURE.md](ARCHITECTURE.md). The build log lives in [PLAN.md](PLAN.md). [CONTEXT.md](CONTEXT.md) is a complete handoff document: current status, infrastructure, and hard-won implementation notes for anyone (human or agent) continuing this project.

## Distribution

The local build is ad-hoc signed and runs on your Mac. For public distribution:

1. `codesign --force --options runtime --sign "Developer ID Application: …" build/NostrBar.app`
2. Notarize: `xcrun notarytool submit build/NostrBar.zip … && xcrun stapler staple …`
3. Optional App Sandbox: all APIs in use (URLSession WebSockets, Keychain, Carbon hotkeys via KeyboardShortcuts, SMAppService, Speech) are sandbox-compatible.

## License

MIT.
