import Foundation
import KeyboardShortcuts

/// Central product constants. Rename the app by editing this file and
/// re-running scripts/build_app.sh.
enum AppConstants {
    static let productName = "NostrBar"
    static let bundleIdentifier = "com.nostrbar.app"
    static let loggingSubsystem = "app.nostrbar"
    static let version = "1.0.0"

    /// Keychain service name for the private key item.
    static let keychainService = bundleIdentifier
    static let keychainAccount = "nostr-private-key"

    /// Default relays (general purpose, currently operating, geographically diverse).
    /// Users can override in Settings.
    static let defaultRelays: [String] = [
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://offchain.pub",
    ]
}

extension KeyboardShortcuts.Name {
    static let newPost = Self("newPost", initial: .init(.n, modifiers: [.control, .option]))
    static let captureSelection = Self("captureSelection", initial: .init(.n, modifiers: [.control, .option, .shift]))
    static let startDictation = Self("startDictation", initial: .init(.d, modifiers: [.control, .option]))
}
