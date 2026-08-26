import AppKit
import ServiceManagement
import KeyboardShortcuts

enum LaunchAtLogin {
    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    static func setEnabled(_ enabled: Bool) throws {
        if enabled {
            try SMAppService.mainApp.register()
        } else {
            try SMAppService.mainApp.unregister()
        }
    }
}

/// Installs and manages the menu bar (status) item.
@MainActor
final class MenuBarController: NSObject, NSMenuDelegate {
    private var statusItem: NSStatusItem?
    private var allRelaysDown = false

    var onNewPost: (() -> Void)?
    var onCaptureSelection: (() -> Void)?
    var onDictate: (() -> Void)?
    var onCopyLastNoteLink: (() -> Void)?
    var onSettings: (() -> Void)?

    /// True when a note link is available to copy; refreshed per menu open.
    var hasLastNoteLink = false

    func install() {
        guard statusItem == nil else { return }
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = Self.icon(allRelaysDown: false)
        item.button?.image?.isTemplate = true

        // Left click opens a new post; right click (or control-click) opens the
        // menu. The menu is popped up programmatically on demand.
        let menu = NSMenu()
        menu.delegate = self
        self.menu = menu
        let button = item.button!
        button.action = #selector(statusItemClicked)
        button.target = self
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem = item
    }

    private var menu: NSMenu?

    @objc private func statusItemClicked() {
        guard let event = NSApp.currentEvent else {
            onNewPost?()
            return
        }
        if event.type == .rightMouseUp
            || (event.type == .leftMouseUp && event.modifierFlags.contains(.control)) {
            showMenu()
        } else {
            onNewPost?()
        }
    }

    private func showMenu() {
        guard let item = statusItem, let button = item.button, let menu else { return }
        item.menu = menu
        button.performClick(nil)
        item.menu = nil
    }

    /// Reflects a serious connectivity problem in the icon (all relays down).
    func setRelayHealth(allDown: Bool) {
        guard allDown != allRelaysDown else { return }
        allRelaysDown = allDown
        statusItem?.button?.image = Self.icon(allRelaysDown: allDown)
    }

    private static func icon(allRelaysDown: Bool) -> NSImage? {
        let name = allRelaysDown ? "exclamationmark.bubble" : "text.bubble"
        let image = NSImage(systemSymbolName: name, accessibilityDescription: AppConstants.productName)
        image?.isTemplate = true
        return image
    }

    /// Rebuilt each time the menu opens so shortcuts and state stay current.
    func menuWillOpen(_ menu: NSMenu) {
        menu.removeAllItems()

        let newPost = NSMenuItem(title: "New Post", action: #selector(newPostClicked), keyEquivalent: "")
        newPost.target = self
        if let shortcut = KeyboardShortcuts.getShortcut(for: .newPost) {
            newPost.setShortcut(shortcut)
        }
        menu.addItem(newPost)

        let capture = NSMenuItem(title: "Capture Selection", action: #selector(captureClicked), keyEquivalent: "")
        capture.target = self
        if let shortcut = KeyboardShortcuts.getShortcut(for: .captureSelection) {
            capture.setShortcut(shortcut)
        }
        menu.addItem(capture)

        let dictate = NSMenuItem(title: "Dictate", action: #selector(dictateClicked), keyEquivalent: "")
        dictate.target = self
        if let shortcut = KeyboardShortcuts.getShortcut(for: .startDictation) {
            dictate.setShortcut(shortcut)
        }
        menu.addItem(dictate)

        let copyLink = NSMenuItem(title: "Copy Last Note Link", action: #selector(copyLinkClicked), keyEquivalent: "")
        copyLink.target = self
        copyLink.isEnabled = hasLastNoteLink
        menu.addItem(copyLink)

        menu.addItem(.separator())

        let settings = NSMenuItem(title: "Settings…", action: #selector(settingsClicked), keyEquivalent: ",")
        settings.target = self
        menu.addItem(settings)

        let quit = NSMenuItem(
            title: "Quit \(AppConstants.productName)",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"
        )
        menu.addItem(quit)
    }

    @objc private func newPostClicked() { onNewPost?() }
    @objc private func captureClicked() { onCaptureSelection?() }
    @objc private func dictateClicked() { onDictate?() }
    @objc private func copyLinkClicked() { onCopyLastNoteLink?() }
    @objc private func settingsClicked() { onSettings?() }
}
