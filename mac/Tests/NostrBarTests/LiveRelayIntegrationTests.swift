import Foundation
import Testing

@testable import NostrBar

/// Live end-to-end publish against public Nostr relays.
///
/// Not part of the regular suite (public relay availability is inherently
/// nondeterministic). Run explicitly with:
///
///     NOSTRBAR_LIVE=1 swift test --filter LiveRelayIntegrationTests
///
/// Uses a disposable identity generated at runtime. Prints only the event id,
/// relay host, and acceptance status.
struct LiveRelayIntegrationTests {
    private struct Skip: Error {}

    @Test func publishesToAPublicRelay() async throws {
        guard ProcessInfo.processInfo.environment["NOSTRBAR_LIVE"] == "1" else {
            print("[live] Skipped: set NOSTRBAR_LIVE=1 to run.")
            return
        }

        // Disposable TEST identity — generated fresh every run.
        let identity = try Keys.generateIdentity()
        print("[live] npub: \(identity.npub)")

        let event = try NostrEventFactory.signedNote(
            content: "NostrBar live integration test \(Int(Date().timeIntervalSince1970))",
            identity: identity
        )
        #expect(NostrEventFactory.internallyValid(event))

        let relays = AppConstants.defaultRelays.compactMap { URL(string: $0) }
        let pool = RelayPool()
        await pool.setRelays(relays)
        defer { Task { await pool.shutdownAll() } }

        // Give warm-up connections a moment.
        try await Task.sleep(for: .seconds(6))

        let accepted = await pool.publish(event)

        if accepted {
            print("[live] event id: \(event.id)")
            print("[live] ACCEPTED by at least one relay")
        } else {
            print("[live] event id: \(event.id)")
            print("[live] no relay accepted (network down? relays changed?)")
        }
        #expect(accepted)
    }
}
