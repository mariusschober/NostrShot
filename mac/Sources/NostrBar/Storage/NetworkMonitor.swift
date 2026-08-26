import Foundation
import Network

/// Tracks general network availability so publishing can fail fast offline.
@MainActor
final class NetworkMonitor {
    /// True when the last observed path was satisfied.
    private(set) var isSatisfied = true
    /// Called on the main actor when the network becomes available again.
    var onBecameSatisfied: (() -> Void)?

    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let wasSatisfied = self.isSatisfied
                self.isSatisfied = satisfied
                if satisfied, !wasSatisfied {
                    self.onBecameSatisfied?()
                }
            }
        }
        monitor.start(queue: DispatchQueue(label: "app.nostrbar.pathmonitor"))
    }

    deinit {
        monitor.cancel()
    }
}
