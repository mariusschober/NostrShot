import AppKit
import ApplicationServices

/// Reads the selected text of the frontmost app via the Accessibility API.
/// Requires the Accessibility permission (System Settings → Privacy & Security).
enum SelectionCapture {
    /// True if this process already has Accessibility trust.
    static func isTrusted(prompt: Bool) -> Bool {
        // Raw constant string: the kAXTrustedCheckOptionPrompt global is not
        // exposed as Sendable under Swift 6 strict concurrency.
        let promptKey = "AXTrustedCheckOptionPrompt" as CFString
        return AXIsProcessTrustedWithOptions([promptKey: prompt] as CFDictionary)
    }

    /// The frontmost app's selected text, or nil.
    static func capture() -> String? {
        guard isTrusted(prompt: false),
              let app = NSWorkspace.shared.frontmostApplication
        else { return nil }

        let axApp = AXUIElementCreateApplication(app.processIdentifier)
        var focusedValue: CFTypeRef?
        guard AXUIElementCopyAttributeValue(axApp, kAXFocusedUIElementAttribute as CFString, &focusedValue) == .success,
              let focused = focusedValue
        else { return nil }

        let element = unsafeDowncast(focused, to: AXUIElement.self)
        var textValue: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXSelectedTextAttribute as CFString, &textValue) == .success,
              let text = textValue as? String,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        return text
    }
}
