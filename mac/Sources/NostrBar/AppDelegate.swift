import AppKit
import KeyboardShortcuts

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let services = AppServices()

    private lazy var composer = ComposerController(services: services)
    private lazy var menuBar = MenuBarController()
    private lazy var settingsWindow = SettingsWindowController(services: services) { [weak self] in
        self?.relaysDidChange()
    }
    private var setupWindow: SetupWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        menuBar.install()
        menuBar.onNewPost = { [weak self] in self?.newPostRequested() }
        menuBar.onCaptureSelection = { [weak self] in self?.captureSelectionRequested() }
        menuBar.onDictate = { [weak self] in self?.dictationRequested() }
        menuBar.onCopyLastNoteLink = { [weak self] in self?.copyLastNoteLink() }
        menuBar.onSettings = { [weak self] in self?.openSettings() }

        KeyboardShortcuts.onKeyDown(for: .newPost) { [weak self] in
            self?.newPostRequested()
        }
        KeyboardShortcuts.onKeyDown(for: .captureSelection) { [weak self] in
            self?.captureSelectionRequested()
        }
        KeyboardShortcuts.onKeyDown(for: .startDictation) { [weak self] in
            self?.dictationRequested()
        }

        composer.onNeedsSetup = { [weak self] in
            self?.showSetup(mode: .initial)
        }

        services.networkMonitor.onBecameSatisfied = { [weak self] in
            guard let self else { return }
            let pool = self.services.relayPool
            Task { await pool.reconnectUnhealthy() }
        }

        // Waking from sleep can leave sockets half-dead even when the network
        // path looks unchanged.
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let pool = self.services.relayPool
                Task { await pool.reconnectUnhealthy() }
            }
        }

        services.relayPool.onAllRelaysDown = { [weak self] allDown in
            Task { @MainActor in
                self?.menuBar.setRelayHealth(allDown: allDown)
            }
        }

        // Development hook: NOSTRBAR_DEBUG_COMPOSER=1 opens the composer at launch.
        if ProcessInfo.processInfo.environment["NOSTRBAR_DEBUG_COMPOSER"] == "1" {
            composer.showOrFocus()
        }

        // Identity loads in the background; UI must never wait on Keychain.
        Task {
            await services.keyManager.loadStoredIdentity()
            if await services.keyManager.hasIdentity {
                await services.relayPool.setRelays(services.preferences.relayURLs)
            } else {
                showSetup(mode: .initial)
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        let pool = services.relayPool
        Task { await pool.shutdownAll() }
    }

    // MARK: - Actions

    func newPostRequested() {
        guard setupWindow == nil else { return }
        // Synchronous: must run within the hotkey's user-interaction context
        // so macOS grants us focus. Identity is checked at publish time.
        composer.showOrFocus()
    }

    private func captureSelectionRequested() {
        guard setupWindow == nil else { return }
        // Capture BEFORE activating our app: the selection belongs to the
        // currently frontmost application.
        let selected = SelectionCapture.capture()
        if selected == nil, !SelectionCapture.isTrusted(prompt: true) {
            // The system permission dialog is showing; composer opens without text.
            menuBar.hasLastNoteLink = services.lastPublishedEvent != nil
        }
        composer.showOrFocus(prefill: selected)
    }

    private func dictationRequested() {
        guard setupWindow == nil else { return }
        composer.startDictation()
    }

    private func copyLastNoteLink() {
        guard let event = services.lastPublishedEvent,
              let link = Nip19.nevent(eventIDHex: event.id, authorHex: event.pubkey, kind: event.kind)
        else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(link, forType: .string)
    }

    func openSettings() {
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow.show()
    }

    private func showSetup(mode: SetupView.Mode) {
        if setupWindow == nil {
            setupWindow = SetupWindowController(keyManager: services.keyManager, mode: mode) { [weak self] in
                guard let self else { return }
                self.setupWindow?.close()
                self.setupWindow = nil
                self.identitySetupFinished()
            }
        }
        setupWindow?.show()
    }

    private func identitySetupFinished() {
        Task {
            if await services.keyManager.hasIdentity {
                await services.relayPool.setRelays(services.preferences.relayURLs)
            }
        }
    }

    private func relaysDidChange() {
        Task {
            await services.relayPool.setRelays(services.preferences.relayURLs)
        }
    }
}
