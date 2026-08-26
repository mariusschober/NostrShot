import Foundation

/// Shared application-wide services. Created once at launch.
@MainActor
final class AppServices {
    var preferences = Preferences()
    let keyManager = KeyManager()
    let relayPool = RelayPool()
    let networkMonitor = NetworkMonitor()

    /// The most recent successfully published event (for "Copy Note Link").
    var lastPublishedEvent: NostrEvent?

    init() {}
}
