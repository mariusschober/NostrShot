import SwiftUI

/// The composer content: a quiet text surface with a minimal footer.
/// Short text sits vertically balanced (Spotlight-style); long text fills the
/// surface and scrolls. During dictation the footer shows a recording cue.
struct ComposerView: View {
    @ObservedObject var controller: ComposerController
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            ComposerTextEditor(
                text: $controller.text,
                focusToken: controller.focusToken,
                caretToEnd: controller.isListening,
                initialCursor: controller.pendingCursor,
                onCursorChange: { controller.cursorMoved(to: $0) }
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .accessibilityLabel("New post")
            .accessibilityHint("Write a note to publish to Nostr")

            footer
        }
        .background(
            VisualEffectBackground()
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(.quaternary, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .frame(width: AppConstants.composerSize.width, height: AppConstants.composerSize.height)
    }

    @ViewBuilder
    private var footer: some View {
        HStack(spacing: 8) {
            if controller.isListening {
                RecordingDot(reduceMotion: reduceMotion)
                Text("Listening… ⌃⌥D to stop")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            } else {
                switch controller.phase {
                case .idle:
                    EmptyView()
                case .failed(let message):
                    Text(message)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Button("Retry") { controller.publish() }
                        .keyboardShortcut(.defaultAction)
                }
            }

            Spacer()

            if controller.isListening {
                Text("⌘↩ Publish")
                    .font(.caption)
                    .foregroundStyle(.quaternary)
            } else if controller.phase == .idle {
                Text("⌘↩ Publish")
                    .font(.caption)
                    .foregroundStyle(.quaternary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: controller.isListening)
    }
}

/// Small pulsing red dot indicating active dictation.
private struct RecordingDot: View {
    let reduceMotion: Bool
    @State private var visible = true

    var body: some View {
        Circle()
            .fill(Color.red)
            .frame(width: 7, height: 7)
            .opacity(visible ? 1 : 0.25)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                    visible = false
                }
            }
            .accessibilityLabel("Recording")
    }
}
