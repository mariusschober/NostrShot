import Foundation
import os

/// Coordinates a small pool of relay connections and concurrent publishing.
actor RelayPool {
    private var connections: [URL: RelayConnection] = [:]
    private let log = Logger(subsystem: AppConstants.loggingSubsystem, category: "relay-pool")
    private var healthDebounceTask: Task<Void, Never>?

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
        // Debounce burst of 4 connects at launch (<1s) into single callback.
        healthDebounceTask?.cancel()
        healthDebounceTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(120))
            guard let self, !Task.isCancelled else { return }
            await self.emitHealth()
        }
    }

    private func emitHealth() async {
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
        healthDebounceTask?.cancel()
        healthDebounceTask = nil
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
    /// Fixed: previous AsyncStream early-return leaked the stream until last
    /// relay finished (3s) and left buffer orphaned on rapid-fire publishes.
    func publish(_ event: NostrEvent) async -> Bool {
        let message = NostrEventFactory.relayMessage(event)
        let targets = Array(connections.values)
        guard !targets.isEmpty else { return false }

        // Use a gate that resumes the outer continuation on first acceptance
        // or last failure, allowing remaining relays to continue publishing
        // in detached Tasks without leaking AsyncStream buffers.
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let gate = PublishGate(count: targets.count, continuation: continuation)
            for connection in targets {
                Task {
                    let result = await connection.publish(id: event.id, messageJSON: message)
                    await gate.report(result)
                }
            }
        }
    }
}

/// Gate that resumes exactly once: on first `.accepted` or when all relays
/// have reported failure. Remaining reports after success are dropped.
private actor PublishGate {
    private var remaining: Int
    private var finished = false
    private let continuation: CheckedContinuation<Bool, Never>

    init(count: Int, continuation: CheckedContinuation<Bool, Never>) {
        self.remaining = count
        self.continuation = continuation
    }

    func report(_ result: RelayConnection.PublishResult) {
        guard !finished else {
            // Still countdown to avoid leak, but don't resume twice.
            remaining -= 1
            return
        }
        if case .accepted = result {
            finished = true
            continuation.resume(returning: true)
            remaining -= 1
            return
        }
        remaining -= 1
        if remaining == 0 {
            finished = true
            continuation.resume(returning: false)
        } else if remaining < 0 {
            assertionFailure("PublishGate underflow")
        }
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
