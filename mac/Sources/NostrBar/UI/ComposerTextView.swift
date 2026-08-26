import AppKit
import SwiftUI

/// SwiftUI bridge to the composer's AppKit text view: exact control over
/// insets and vertical balance, native undo/redo/copy/paste, immediate
/// first responder.
struct ComposerTextEditor: NSViewRepresentable {
    @Binding var text: String
    /// Incremented whenever the composer becomes visible; refocuses the editor.
    var focusToken: Int
    /// While dictating, programmatic text changes keep the caret at the end.
    var caretToEnd = false
    /// Caret location to restore on the next focus (draft reopen).
    var initialCursor: Int?
    /// Reports caret moves so the draft cursor can persist.
    var onCursorChange: ((Int) -> Void)?

    func makeNSView(context: Context) -> NSScrollView {
        let scrollView = NSScrollView()
        scrollView.hasVerticalScroller = true
        scrollView.autohidesScrollers = true
        scrollView.drawsBackground = false
        scrollView.scrollerStyle = .overlay
        scrollView.verticalScrollElasticity = .none

        let textView = Self.makeTextView()
        textView.delegate = context.coordinator
        textView.string = text
        context.coordinator.lastReportedText = text
        scrollView.documentView = textView

        // The text view is the source of truth while the user types: report
        // upward, never write back into it here.
        let coordinator = context.coordinator
        textView.onTextChange = { value in
            coordinator.lastReportedText = value
            text = value
        }

        DispatchQueue.main.async { [weak scrollView] in
            scrollView?.window?.makeFirstResponder(textView)
        }
        return scrollView
    }

    func updateNSView(_ scrollView: NSScrollView, context: Context) {
        guard let textView = scrollView.documentView as? ComposerTextView else { return }
        context.coordinator.cursorHandler = onCursorChange

        // Only apply changes that did not originate from the text view itself
        // (dictation, prefill, draft restore, clear-after-publish). Writing a
        // stale render back over newer keystrokes would eat input.
        if text != context.coordinator.lastReportedText {
            context.coordinator.lastReportedText = text
            textView.string = text
            if caretToEnd {
                textView.setSelectedRange(NSRange(location: (text as NSString).length, length: 0))
            }
        }
        textView.recenterIfNeeded()

        if context.coordinator.lastFocusToken != focusToken {
            context.coordinator.lastFocusToken = focusToken
            scrollView.window?.makeFirstResponder(textView)
            if let initialCursor {
                let location = min(initialCursor, (textView.string as NSString).length)
                textView.setSelectedRange(NSRange(location: location, length: 0))
            } else if caretToEnd {
                textView.setSelectedRange(NSRange(location: (textView.string as NSString).length, length: 0))
            }
            textView.scrollToVisible(textView.visibleRect)
        }
        textView.recenterIfNeeded()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(lastFocusToken: focusToken)
    }

    // MARK: - Internals

    private static func makeTextView() -> ComposerTextView {
        let view = ComposerTextView()
        view.isRichText = false
        view.allowsUndo = true
        view.usesFontPanel = false
        view.isAutomaticQuoteSubstitutionEnabled = false
        view.isAutomaticDashSubstitutionEnabled = false
        view.isContinuousSpellCheckingEnabled = false
        view.isGrammarCheckingEnabled = false
        view.font = .systemFont(ofSize: 17)
        view.textColor = .labelColor
        view.drawsBackground = false
        view.textContainerInset = .zero
        view.textContainer?.lineFragmentPadding = 4
        view.isVerticallyResizable = true
        view.autoresizingMask = [.width]
        view.minSize = .zero
        view.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        return view
    }

    /// Vertically balances short text inside the visible area.
    private static func centerContent(in scrollView: NSScrollView?) {
        guard let scrollView,
              let textView = scrollView.documentView as? ComposerTextView,
              scrollView.contentSize.height > 0,
              let layoutManager = textView.layoutManager,
              let container = textView.textContainer
        else { return }
        let visible = scrollView.contentSize.height
        let content = layoutManager.usedRect(for: container).height
        textView.textContainerInset = NSSize(width: 0, height: max(0, (visible - content - 2) / 2))
    }
}

/// Representable state: focus token bookkeeping and cursor reporting.
final class Coordinator: NSObject, NSTextViewDelegate {
    var lastFocusToken: Int
    var cursorHandler: ((Int) -> Void)?
    /// The most recent text the text view itself reported; used to distinguish
    /// user edits from external binding changes.
    var lastReportedText: String?

    init(lastFocusToken: Int) {
        self.lastFocusToken = lastFocusToken
    }

    func textViewDidChangeSelection(_ notification: Notification) {
        guard let textView = notification.object as? NSTextView else { return }
        cursorHandler?(textView.selectedRange.location)
    }
}

/// NSTextView that reports changes, tracks its scroll view's width, and draws
/// its own placeholder so it stays aligned with dynamically centered text.
final class ComposerTextView: NSTextView {
    var onTextChange: ((String) -> Void)?
    var placeholder = "What's on your mind?"

    override func didChangeText() {
        super.didChangeText()
        onTextChange?(string)
        needsDisplay = true
    }

    override var textContainerInset: NSSize {
        didSet { needsDisplay = true }
    }

    override func draw(_ dirtyRect: NSRect) {
        trackScrollViewWidth()
        recenterIfNeeded()
        super.draw(dirtyRect)
        guard string.isEmpty else { return }
        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 17),
            .foregroundColor: NSColor.tertiaryLabelColor,
        ]
        let origin = NSPoint(
            x: textContainerOrigin.x + (textContainer?.lineFragmentPadding ?? 0),
            y: textContainerOrigin.y + textContainerInset.height
        )
        (placeholder as NSString).draw(at: origin, withAttributes: attributes)
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        trackScrollViewWidth()
        recenterIfNeeded()
    }

    private func trackScrollViewWidth() {
        if let scrollView = enclosingScrollView, frame.width != scrollView.contentSize.width {
            frame.size.width = scrollView.contentSize.width
        }
    }

    /// Vertically balances short text inside the visible area.
    func recenterIfNeeded() {
        guard let scrollView = enclosingScrollView,
              scrollView.contentSize.height > 0,
              let layoutManager,
              let container = textContainer
        else { return }
        let visible = scrollView.contentSize.height
        let content = layoutManager.usedRect(for: container).height
        let inset = max(0, (visible - content - 2) / 2)
        if textContainerInset.height != inset {
            textContainerInset = NSSize(width: 0, height: inset)
        }
    }

    override var acceptsFirstResponder: Bool { true }
}
