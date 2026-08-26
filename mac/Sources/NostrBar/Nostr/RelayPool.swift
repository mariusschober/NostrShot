import Foundation
import os

/// Coordinates a small pool of relay connections and concurrent publishing.
actor RelayPool {
    private var connections: [URL: RelayConnection] = [:]
    private let log = Logger(subsystem: AppConstants.loggingSubsystem, category: "relay-pool")

    /// Called on a background task whenever aggregate health may have changed.
    /// `true` means every configured relay is currently disconnected.
    nonisolated(unsafe) var onAllRelaysDown: (@Sendable (Bool) -> Void)?

    /// Reconciles the configured relay set: connects new, shuts down removed.
    func setRelays(_ urls: [URL]) {
        let newURLs = Set(urls)
        let oldURLs = Set(connections.keys)

        for removed in oldURLs.subtracting(newURLs) {
            guard let connection = connections.removeValue(forKey: removed) else { continue }
            Task { await connection.shutdown() }
        }
        for added in newURLs.subtracting(connections.keys) {
            let connection = RelayConnection(url: added)
            connection.onStateChange = { [weak self] in
                Task { await self?.healthDidChange() }
            }
            connections[added] = connection
            Task { await connection.connect() }
        }
    }

    private func healthDidChange() async {
        var allDown = !connections.isEmpty
        for connection in connections.values {
            if await connection.state == .connected {
                allDown = false
                break
            }
        }
        onAllRelaysDown?(allDown)
    }

    func connectAll() {
        for connection in connections.values {
            Task { await connection.connect() }
        }
    }

    /// Called when the network path changes or the Mac wakes.
    func reconnectUnhealthy() {
        for connection in connections.values {
            Task { await connection.reconnectNow() }
        }
    }

    func shutdownAll() {
        for connection in connections.values {
            Task { await connection.shutdown() }
        }
        connections.removeAll()
    }

    struct StatusEntry: Sendable {
        let url: URL
        let state: RelayState
    }

    func statusSnapshot() async -> [StatusEntry] {
        var entries: [StatusEntry] = []
        for url in connections.keys.sorted(by: { $0.absoluteString < $1.absoluteString }) {
            guard let connection = connections[url] else { continue }
            entries.append(StatusEntry(url: url, state: await connection.state))
        }
        return entries
    }

    /// Publishes one signed event to every relay concurrently.
    ///
    /// Returns `true` as soon as any relay accepts. Remaining relays continue
    /// broadcasting in the background; their responses resolve independently.
    func publish(_ event: NostrEvent) async -> Bool {
        let message = NostrEventFactory.relayMessage(event)
        let targets = Array(connections.values)
        guard !targets.isEmpty else { return false }

        // Every relay completes on its own bounded deadline; the stream ends
        // when the last result arrives. Early return on first acceptance —
        // remaining relays keep broadcasting independently.
        let box = CountdownBox(count: targets.count)
        let results = AsyncStream<RelayConnection.PublishResult> { continuation in
            for connection in targets {
                Task {
                    let result = await connection.publish(id: event.id, messageJSON: message)
                    continuation.yield(result)
                    if box.isLast() {
                        continuation.finish()
                    }
                }
            }
        }

        for await result in results {
            if case .accepted = result {
                return true
            }
        }
        return false
    }
}

/// Thread-safe one-shot countdown used to finish the publish result stream
/// exactly once, after every relay has reported.
private final class CountdownBox: @unchecked Sendable {
    private let lock = NSLock()
    private var count: Int

    init(count: Int) {
        self.count = count
    }

    /// Decrements and returns true exactly once, when the count hits zero.
    func isLast() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        count -= 1
        if count == 0 {
            return true
        }
        if count < 0 {
            assertionFailure("CountdownBox underflow")
        }
        return false
    }
}

/// Normalizes and validates a user-provided relay URL string.
enum RelayURLNormalizer {
    static func normalize(_ input: String) -> URL? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard trimmed.hasPrefix("wss://"), trimmed.count > "wss://".count else { return nil }
        guard var components = URLComponents(string: trimmed) else { return nil }
        guard let host = components.host, !host.isEmpty else { return nil }
        if components.path == "/" { components.path = "" }
        components.query = nil
        components.fragment = nil
        return components.url
    }
}
