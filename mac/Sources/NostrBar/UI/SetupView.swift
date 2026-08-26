import SwiftUI

/// First-run / replace-key window content. The key is validated before it is
/// stored, then only the derived npub is shown.
struct SetupView: View {
    enum Mode {
        case initial
        case replace
    }

    let mode: Mode
    let keyManager: KeyManager
    var onDone: () -> Void

    @State private var keyInput = ""
    @State private var errorText: String?
    @State private var importedNpub: String?
    @State private var isImporting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text(mode == .initial ? "Add your Nostr private key" : "Replace your private key")
                    .font(.headline)
                Text("It stays on this Mac and is used locally to sign posts.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            if let importedNpub {
                confirmedContent(importedNpub)
            } else {
                importContent
            }
        }
        .padding(24)
        .frame(width: 380)
    }

    @ViewBuilder
    private func confirmedContent(_ npub: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Connected as")
                .font(.callout)
                .foregroundStyle(.secondary)
            Text(truncated(npub))
                .font(.system(.body, design: .monospaced))
                .textSelection(.enabled)

            HStack {
                Spacer()
                Button("Continue") { onDone() }
                    .keyboardShortcut(.defaultAction)
            }
        }
    }

    @ViewBuilder
    private var importContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            SecureField("nsec1… or 64 hex characters", text: $keyInput)
                .textFieldStyle(.roundedBorder)
                .font(.system(.body, design: .monospaced))
                .disabled(isImporting)

            if let errorText {
                Text(errorText)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            HStack(spacing: 8) {
                Spacer()
                Button("Cancel") {
                    keyInput = ""
                    errorText = nil
                    onDone()
                }
                .keyboardShortcut(.cancelAction)
                Button(isImporting ? "Importing…" : "Import") { importKey() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(keyInput.trimmingCharacters(in: .whitespaces).isEmpty || isImporting)
            }
        }
    }

    private func importKey() {
        isImporting = true
        errorText = nil
        let input = keyInput
        Task {
            do {
                importedNpub = try await keyManager.importKey(input)
                keyInput = ""
            } catch {
                errorText = friendly(error)
            }
            isImporting = false
        }
    }

    private func friendly(_ error: Error) -> String {
        if case KeyManager.ImportError.invalidKey(let message)? = error as? KeyManager.ImportError {
            return message
        }
        return "Couldn't read this key."
    }

    private func truncated(_ npub: String) -> String {
        guard npub.count > 20 else { return npub }
        return "\(npub.prefix(12))…\(npub.suffix(6))"
    }
}

extension KeyManager.ImportError {
    var userMessage: String {
        switch self {
        case .invalidKey(let message): message
        case .keychainFailure: "Couldn't store the key in your Keychain."
        }
    }
}
