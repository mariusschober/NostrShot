import AppKit
import SwiftUI

/// Small utility-style windows hosting SwiftUI content.
@MainActor
final class SettingsWindowController {
    let window: NSWindow

    init(services: AppServices, onRelaysChanged: @escaping () -> Void) {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 420),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "\(AppConstants.productName) Settings"
        window.isReleasedWhenClosed = false
        window.center()
        window.contentView = NSHostingView(rootView: SettingsView(services: services, onRelaysChanged: onRelaysChanged))
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }
}

@MainActor
final class SetupWindowController {
    let window: NSWindow

    init(keyManager: KeyManager, mode: SetupView.Mode, onDone: @escaping () -> Void) {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 380, height: 220),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        window.title = "Welcome to \(AppConstants.productName)"
        window.isReleasedWhenClosed = false
        window.center()
        window.contentView = NSHostingView(rootView: SetupView(mode: mode, keyManager: keyManager, onDone: onDone))
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    func close() {
        window.orderOut(nil)
    }
}
