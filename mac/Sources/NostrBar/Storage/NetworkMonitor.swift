import Foundation
import Network

/// Tracks general network availability so publishing can fail fast offline.
/// Debounced to avoid churn from rapid NWPath flaps (e.g. wake/network switch).
@MainActor
final class NetworkMonitor {
    /// True when the last observed path was satisfied.
    private(set) var isSatisfied = true
    /// Called on the main actor when the network becomes available again.
    var onBecameSatisfied: (() -> Void)?

    private let monitor = NWPathMonitor()
    private var debounceTask: Task<Void, Never>?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                // Debounce 500ms to coalesce rapid path changes.
                self.debounceTask?.cancel()
                self.debounceTask = Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(500))
                    guard !Task.isCancelled else { return }
                    let wasSatisfied = self.isSatisfied
                    self.isSatisfied = satisfied
                    if satisfied, !wasSatisfied {
                        self.onBecameSatisfied?()
                    }
                }
            }
        }
        monitor.start(queue: DispatchQueue(label: "app.nostrbar.pathmonitor"))
    }

    deinit {
        monitor.cancel()
        debounceTask?.cancel()
    }
}
