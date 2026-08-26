import AppKit
import SwiftUI

/// Owns the composer panel, its lifecycle, and the publish pipeline.
///
/// Publishing is optimistic: ⌘↩ hides the panel immediately and the note is
/// signed and dispatched in the background. The text is held in `pendingNotes`
/// until a relay accepts; on total failure it is restored into the composer.
@MainActor
final class ComposerController: NSObject, ObservableObject {
    enum Phase: Equatable {
        case idle
        case failed(String)
    }

    struct PendingNote {
        let id = UUID().uuidString
        let body: String
    }

    @Published private(set) var phase: Phase = .idle
    @Published var text: String {
        didSet { scheduleDraftSave() }
    }
    @Published private(set) var isListening = false
    /// Incremented every time the composer becomes visible; drives refocusing.
    @Published private(set) var focusToken = 0
    /// Caret to restore on the next focus; consumed by the editor.
    @Published private(set) var pendingCursor: Int?

    private let services: AppServices
    private let panel: ComposerPanel
    private var keyMonitor: Any?
    private var pendingNotes: [PendingNote] = []
    private var isClosing = false
    private var draftSaveTask: Task<Void, Never>?
    private lazy var dictation = DictationController()

    /// Called when a publish attempt reveals there is no configured identity.
    var onNeedsSetup: (() -> Void)?

    var isVisible: Bool { panel.isVisible }

    init(services: AppServices) {
        self.services = services
        text = services.preferences.draftText
        panel = ComposerPanel(content: NSView())
        super.init()
        let hosting = NSHostingView(rootView: ComposerView(controller: self))
        hosting.frame = NSRect(origin: .zero, size: AppConstants.composerSize)
        panel.contentView = hosting
        panel.setContentSize(AppConstants.composerSize)
        panel.onResignKey = { [weak self] in self?.handleResignKey() }
    }

    // MARK: - Visibility

    func showOrFocus(prefill: String? = nil) {
        let wasVisible = isVisible
        if !wasVisible {
            positionOnActiveScreen()
            pendingCursor = prefill == nil ? services.preferences.draftCursor : nil
        }
        if let prefill {
            text = prefill
            phase = .idle
        }
        focusToken += 1
        installKeyMonitor()
        bringToFront()
        if !wasVisible { animateIn() }
    }

    private var cursorSaveTask: Task<Void, Never>?

    /// Persists the caret while the user edits the draft (debounced).
    func cursorMoved(to location: Int) {
        guard isVisible, !isListening, !isClosing else { return }
        cursorSaveTask?.cancel()
        cursorSaveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.services.preferences.draftCursor = location
            }
        }
    }

    func hide() {
        guard isVisible, !isClosing else { return }
        flushDraftSave()
        removeKeyMonitor()
        animateOut()
    }

    private func bringToFront() {
        if NSApp.isHidden { NSApp.unhide(nil) }
        // The modern activate() refuses to steal focus unless called within a
        // user-interaction context; launchers and palettes have relied on
        // ignoringOtherApps for this exact scenario for years.
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
        if !panel.isVisible {
            // Background accessory apps sometimes have orderFront ignored.
            panel.orderFrontRegardless()
            panel.makeKey()
        }
    }

    private func handleResignKey() {
        guard isVisible, !isClosing else { return }
        flushDraftSave()
        removeKeyMonitor()
        panel.orderOut(nil)
        NSApp.deactivate()
    }

    private func scheduleDraftSave() {
        draftSaveTask?.cancel()
        let current = text
        draftSaveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self else { return }
                // Only write if text hasn't changed again (coalesced)
                if self.text == current {
                    self.services.preferences.draftText = current
                }
            }
        }
    }

    private func flushDraftSave() {
        draftSaveTask?.cancel()
        draftSaveTask = nil
        services.preferences.draftText = text
    }

    /// Opens centered on the screen the user is working on (screen with mouse).
    private func positionOnActiveScreen() {
        let mouseLocation = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { $0.frame.contains(mouseLocation) } ?? NSScreen.main
        guard let visibleFrame = screen?.visibleFrame else { return }

        let size = panel.frame.size
        let origin = CGPoint(
            x: visibleFrame.midX - size.width / 2,
            y: visibleFrame.midY - size.height / 2
        )
        panel.setFrameOrigin(origin)
    }

    // MARK: - Animation

    private var reduceMotion: Bool {
        NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
    }

    private func animateIn() {
        isClosing = false
        guard !reduceMotion else { return }
        panel.alphaValue = 0
        if let content = panel.contentView {
            content.wantsLayer = true
            content.layer?.setAffineTransform(CGAffineTransform(scaleX: 0.98, y: 0.98))
        }
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.12
            context.timingFunction = CAMediaTimingFunction(name: .easeOut)
            panel.animator().alphaValue = 1
        }, completionHandler: { [weak self] in
            // Completion handlers run on the main thread.
            MainActor.assumeIsolated {
                self?.panel.contentView?.layer?.setAffineTransform(.identity)
            }
        })
    }

    private func animateOut() {
        guard !reduceMotion else {
            finishClose()
            return
        }
        isClosing = true
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.09
            context.timingFunction = CAMediaTimingFunction(name: .easeIn)
            panel.animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            MainActor.assumeIsolated {
                self?.finishClose()
            }
        })
    }

    private func finishClose() {
        panel.orderOut(nil)
        panel.alphaValue = 1
        isClosing = false
        NSApp.deactivate()
    }

    // MARK: - Publishing (optimistic)

    func publish() {
        if isListening { stopDictation() }

        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }

        let note = PendingNote(body: body)
        pendingNotes.append(note)
        // Flush draft before clearing to avoid debounce write racing with clear.
        flushDraftSave()
        text = ""
        services.preferences.draftText = ""
        hide()
        phase = .idle

        Task { await publishInBackground(note) }
    }

    private func publishInBackground(_ note: PendingNote) async {
        guard services.networkMonitor.isSatisfied else {
            fail(note, "You're offline. Your note is safe here.")
            return
        }
        do {
            let event = try await services.keyManager.signNote(content: note.body)
            let accepted = await services.relayPool.publish(event)
            guard accepted else {
                fail(note, "Couldn't reach a relay. Your note is safe here.")
                return
            }
            pendingNotes.removeAll { $0.id == note.id }
            services.lastPublishedEvent = event
            AppSound.play(.success, enabled: services.preferences.playSound)
        } catch {
            if await services.keyManager.hasIdentity {
                fail(note, "Couldn't sign your note. Your text is safe here.")
            } else {
                pendingNotes.removeAll { $0.id == note.id }
                restoreDraft(note.body)
                hide()
                onNeedsSetup?()
            }
        }
    }

    private func fail(_ note: PendingNote, _ message: String) {
        pendingNotes.removeAll { $0.id == note.id }
        AppSound.play(.failure, enabled: services.preferences.playSound)
        restoreDraft(note.body)
        phase = .failed(message)
        focusToken += 1
        showOrFocus()
    }

    /// Puts a failed note back into the editor, preserving anything typed since.
    private func restoreDraft(_ body: String) {
        text = DraftRestore.merge(pending: body, current: text)
    }

    // MARK: - Dictation

    func startDictation() {
        if isListening {
            stopDictation()
            return
        }
        if !isVisible { showOrFocus() }
        dictation.onUpdate = { [weak self] transcript in
            guard let self, self.isListening else { return }
            self.text = transcript
        }
        dictation.onUnavailable = { [weak self] message in
            guard let self else { return }
            self.isListening = false
            self.phase = .failed(message)
            self.focusToken += 1
        }
        phase = .idle
        isListening = true
        focusToken += 1
        Task { await dictation.start() }
    }

    func stopDictation() {
        guard isListening else { return }
        dictation.stop()
        isListening = false
        focusToken += 1
    }

    // MARK: - Key handling

    private func installKeyMonitor() {
        removeKeyMonitor()
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            self?.handleKeyEvent(event) ?? event
        }
    }

    private func removeKeyMonitor() {
        if let monitor = keyMonitor {
            NSEvent.removeMonitor(monitor)
            keyMonitor = nil
        }
    }

    private func handleKeyEvent(_ event: NSEvent) -> NSEvent? {
        guard panel.isKeyWindow, event.window === panel else { return event }

        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask).subtracting([.shift, .capsLock])
        if event.keyCode == 36, modifiers == [.command] { // ⌘Return
            publish()
            return nil
        }
        if event.keyCode == 53, modifiers.isEmpty { // Escape
            stopDictation()
            hide()
            return nil
        }
        return event
    }
}

/// Pure merge rule for restoring failed notes; unit-tested.
enum DraftRestore {
    static func merge(pending: String, current: String) -> String {
        let trimmedCurrent = current.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedCurrent.isEmpty { return pending }
        return pending + "\n\n" + current
    }
}
