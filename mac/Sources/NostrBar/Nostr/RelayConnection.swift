import Foundation
import os

/// Connection state of a single relay.
enum RelayState: Equatable, Sendable {
    case disconnected
    case connecting
    case connected
}

/// One WebSocket connection to one relay, with automatic reconnect and backoff.
///
/// Publishes are awaited against a hard deadline; continuations are always resumed
/// exactly once (by an `OK` response, by disconnect cleanup, or by the deadline).
actor RelayConnection {
    let url: URL

    private(set) var state: RelayState = .disconnected

    private var session: URLSession?
    private var webSocket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var reconnectAttempt = 0
    private var shuttingDown = false
    private var openWaiters: [CheckedContinuation<Bool, Never>] = []
    /// Pending publishes keyed by lowercase event id.
    private var pendingDeadlines: [String: Task<Void, Never>] = [:]

    /// Fired (from the actor) after every connection-state transition.
    nonisolated(unsafe) var onStateChange: (@Sendable () -> Void)?

    private static let pingInterval: Duration = .seconds(30)

    private let log = Logger(subsystem: AppConstants.loggingSubsystem, category: "relay")

    init(url: URL) {
        self.url = url
    }

    // MARK: - Lifecycle

    func connect() {
        guard !shuttingDown else { return }
        guard state == .disconnected else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        session?.invalidateAndCancel()
        session = nil
        state = .connecting
        log.debug("connecting to \(self.url.host ?? "?", privacy: .public)")

        let box = SessionBox()
        sessionBox = box
        let delegate = Delegate(
            onOpen: { [weak self] in Task { await self?.handleOpen(from: box) } },
            onClose: { [weak self] error in Task { await self?.handleClose(error, from: box) } }
        )
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 10
        configuration.waitsForConnectivity = false
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        self.session = session
        let task = session.webSocketTask(with: url)
        webSocket = task
        task.resume()
        startReceiveLoop()
    }

    func shutdown() {
        shuttingDown = true
        reconnectTask?.cancel()
        reconnectTask = nil
        teardown(error: nil)
    }

    /// Forces an immediate reconnect attempt (e.g. network came back).
    func reconnectNow() {
        guard !shuttingDown else { return }
        if state == .connected { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectAttempt = 0
        if state == .disconnected {
            connect()
        } else {
            // Stuck connecting: tear down and retry fresh.
            teardown(error: nil)
            connect()
        }
    }

    deinit {
        session?.invalidateAndCancel()
    }

    // MARK: - Publishing

    enum PublishResult: Sendable {
        case accepted(String)
        case rejected(String)
        case unreachable
    }

    /// Sends a signed event and waits for this relay's `OK` response.
    func publish(id eventID: String, messageJSON: String, deadline: Duration = .seconds(3)) async -> PublishResult {
        guard await waitUntilConnected(deadline: .seconds(1.5)) else { return .unreachable }
        guard let webSocket else { return .unreachable }

        do {
            try await webSocket.send(.string(messageJSON))
        } catch {
            log.debug("send failed to \(self.url.host ?? "?", privacy: .public)")
            return .unreachable
        }

        return await withCheckedContinuation { (continuation: CheckedContinuation<PublishResult, Never>) in
            pending[eventID] = continuation
            scheduleDeadline(for: eventID, deadline)
        }
    }

    /// Pending publishes keyed by lowercase event id.
    private var pending: [String: CheckedContinuation<PublishResult, Never>] = [:]

    private func scheduleDeadline(for eventID: String, _ deadline: Duration) {
        pendingDeadlines[eventID] = Task { [weak self] in
            try? await Task.sleep(for: deadline)
            guard let self, !Task.isCancelled else { return }
            await self.finish(eventID, .unreachable)
        }
    }

    /// Resolves a pending publish exactly once.
    private func finish(_ eventID: String, _ result: PublishResult) {
        guard let continuation = pending.removeValue(forKey: eventID) else { return }
        pendingDeadlines.removeValue(forKey: eventID)?.cancel()
        continuation.resume(returning: result)
    }

    // MARK: - Internals: connection events

    /// Identity token so callbacks from an invalidated old session can be
    /// distinguished from the live one (invalidation fires didCompleteWithError
    /// asynchronously, which would otherwise tear down the fresh connection).
    private final class SessionBox: @unchecked Sendable {}

    private var sessionBox: SessionBox?

    private func handleOpen(from box: SessionBox) {
        guard box === sessionBox, state == .connecting else { return }
        state = .connected
        reconnectAttempt = 0
        log.debug("connected to \(self.url.host ?? "?", privacy: .public)")
        resumeWaiters(connected: true)
        startPingLoop()
        onStateChange?()
    }

    private func handleClose(_ error: Error?, from box: SessionBox) {
        guard box === sessionBox, state != .disconnected else { return }
        log.debug("disconnected from \(self.url.host ?? "?", privacy: .public)")
        teardown(error: error)
        scheduleReconnect()
        onStateChange?()
    }

    private func teardown(error: Error?) {
        state = .disconnected
        pingTask?.cancel()
        pingTask = nil
        receiveTask?.cancel()
        receiveTask = nil
        webSocket?.cancel(with: .goingAway, reason: nil)
        webSocket = nil
        for waiter in openWaiters.drain() {
            waiter.resume(returning: false)
        }
        for id in Array(pending.keys) {
            finish(id, .unreachable)
        }
    }

    private func scheduleReconnect() {
        guard !shuttingDown else { return }
        let delay = Self.backoffDelay(attempt: reconnectAttempt)
        reconnectAttempt += 1
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self, !Task.isCancelled else { return }
            await self.connectAfterBackoff()
        }
    }

    private func connectAfterBackoff() {
        reconnectTask = nil
        connect()
    }

    nonisolated private static func backoffDelay(attempt: Int) -> Duration {
        let seconds = min(UInt64(1) << min(UInt64(attempt), 6), UInt64(60))
        return .seconds(seconds)
    }

    // MARK: - Internals: waiting

    /// Waits until the relay is connected or the deadline passes.
    private func waitUntilConnected(deadline: Duration) async -> Bool {
        switch state {
        case .connected:
            return true
        case .disconnected where !shuttingDown:
            connect()
        default:
            break
        }
        if state == .connected { return true }
        guard !shuttingDown else { return false }

        return await withTaskGroup(of: Bool.self) { group in
            group.addTask { await self.suspendUntilOpen() }
            group.addTask {
                try? await Task.sleep(for: deadline)
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
    }

    private func suspendUntilOpen() async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            openWaiters.append(continuation)
        }
    }

    private func resumeWaiters(connected: Bool) {
        for waiter in openWaiters.drain() {
            waiter.resume(returning: connected)
        }
    }

    // MARK: - Internals: I/O loops

    private func startReceiveLoop() {
        receiveTask = Task { [weak self] in
            await self?.receiveLoop()
        }
    }

    private func receiveLoop() async {
        while !Task.isCancelled, state != .disconnected, let webSocket {
            do {
                let message = try await webSocket.receive()
                switch message {
                case .string(let text):
                    handleMessage(Data(text.utf8))
                case .data(let data):
                    handleMessage(Data(data))
                @unknown default:
                    break
                }
            } catch {
                guard !Task.isCancelled else { return }
                handleClose(error, from: sessionBox ?? SessionBox())
                return
            }
        }
    }

    private func handleMessage(_ data: Data) {
        guard let message = RelayMessage.parse(data) else { return }
        switch message {
        case .ok(let eventID, let accepted, let relayMessage):
            let result: PublishResult = accepted ? .accepted(relayMessage) : .rejected(relayMessage)
            finish(eventID, result)
        case .notice(let text):
            log.debug("NOTICE from \(self.url.host ?? "?", privacy: .public): \(text, privacy: .public)")
        case .ignored:
            break
        }
    }

    private func startPingLoop() {
        pingTask?.cancel()
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.pingInterval)
                guard let self, !Task.isCancelled else { return }
                await self.sendPing()
            }
        }
    }

    private func sendPing() {
        guard state == .connected, let webSocket else { return }
        let box = sessionBox
        webSocket.sendPing { [weak self] error in
            guard error != nil, let box else { return }
            Task { await self?.handleClose(error, from: box) }
        }
    }
}

private final class Delegate: NSObject, URLSessionWebSocketDelegate, Sendable {
    private let onOpen: @Sendable () -> Void
    private let onClose: @Sendable (Error?) -> Void

    init(onOpen: @escaping @Sendable () -> Void,
         onClose: @escaping @Sendable (Error?) -> Void) {
        self.onOpen = onOpen
        self.onClose = onClose
    }

    func urlSession(_: URLSession, webSocketTask _: URLSessionWebSocketTask, didOpenWithProtocol _: String?) {
        onOpen()
    }

    func urlSession(_: URLSession, task _: URLSessionTask, didCompleteWithError error: Error?) {
        onClose(error)
    }
}

private extension Array {
    mutating func drain() -> [Element] {
        let copy = self
        removeAll()
        return copy
    }
}
