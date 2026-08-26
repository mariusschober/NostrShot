import KeyboardShortcuts
import SwiftUI

/// The complete (small) settings surface: account, shortcut, relays, general.
struct SettingsView: View {
    @StateObject private var model: SettingsModel
    @State private var showingReplaceKey = false

    init(services: AppServices, onRelaysChanged: @escaping () -> Void) {
        _model = StateObject(wrappedValue: SettingsModel(services: services, onRelaysChanged: onRelaysChanged))
    }

    var body: some View {
        Form {
            Section("Account") {
                HStack {
                    Text(model.npubDisplay)
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                    Spacer()
                    Button("Replace Private Key…") { showingReplaceKey = true }
                }
            }

            Section("Shortcuts") {
                KeyboardShortcuts.Recorder("New Post", name: .newPost)
                KeyboardShortcuts.Recorder("Capture Selection", name: .captureSelection)
                KeyboardShortcuts.Recorder("Dictate", name: .startDictation)
                Text("Capture Selection needs the Accessibility permission; press its shortcut once to grant it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Relays") {
                ForEach(model.relays, id: \.absoluteString) { url in
                    HStack(spacing: 8) {
                        Circle()
                            .fill(model.relayStates[url] == .connected ? Color.green : Color.secondary.opacity(0.4))
                            .frame(width: 7, height: 7)
                            .help(model.relayStates[url] == .connected ? "Connected" : "Not connected")
                        Text(url.absoluteString)
                            .font(.system(.callout, design: .monospaced))
                            .lineLimit(1)
                        Spacer()
                        Button {
                            model.remove(url)
                        } label: {
                            Image(systemName: "minus.circle")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Remove \(url.absoluteString)")
                    }
                }

                HStack {
                    TextField("wss://relay.example.com", text: $model.newRelayText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { model.add() }
                    Button("Add Relay") { model.add() }
                        .disabled(!model.canAdd)
                }
                if let error = model.relayError {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
            }

            Section("General") {
                Toggle("Launch at Login", isOn: $model.launchAtLogin)
                Toggle("Publish Sound", isOn: $model.playSound)
            }
        }
        .formStyle(.grouped)
        .frame(width: 480, height: 420)
        .task { await model.refresh() }
        .sheet(isPresented: $showingReplaceKey) {
            SetupView(mode: .replace, keyManager: model.services.keyManager) {
                showingReplaceKey = false
                Task { await model.refresh() }
            }
        }
    }
}

@MainActor
final class SettingsModel: ObservableObject {
    @Published var relays: [URL] = []
    @Published var relayStates: [URL: RelayState] = [:]
    @Published var npubDisplay = "No account"
    @Published var newRelayText = ""
    @Published var relayError: String?
    @Published var launchAtLogin: Bool {
        didSet {
            guard oldValue != launchAtLogin else { return }
            do {
                try LaunchAtLogin.setEnabled(launchAtLogin)
            } catch {
                launchAtLogin = oldValue
                relayError = "Couldn't update login item."
            }
        }
    }
    @Published var playSound: Bool {
        didSet {
            guard oldValue != playSound else { return }
            services.preferences.playSound = playSound
        }
    }

    let services: AppServices
    private let onRelaysChanged: () -> Void

    init(services: AppServices, onRelaysChanged: @escaping () -> Void) {
        self.services = services
        self.onRelaysChanged = onRelaysChanged
        relays = services.preferences.relayURLs
        launchAtLogin = LaunchAtLogin.isEnabled
        playSound = services.preferences.playSound
    }

    var canAdd: Bool {
        !newRelayText.trimmingCharacters(in: .whitespaces).isEmpty
    }

    func add() {
        guard let url = RelayURLNormalizer.normalize(newRelayText) else {
            relayError = "Relay URLs look like wss://relay.example.com"
            return
        }
        guard !relays.contains(url) else {
            relayError = "Already in the list."
            return
        }
        relayError = nil
        newRelayText = ""
        relays.append(url)
        save()
    }

    func remove(_ url: URL) {
        relays.removeAll { $0 == url }
        save()
    }

    private func save() {
        services.preferences.relayURLs = relays
        onRelaysChanged()
        Task { await refreshStates() }
    }

    func refresh() async {
        if let npub = await services.keyManager.identity?.npub {
            npubDisplay = truncated(npub)
        } else {
            npubDisplay = "No account"
        }
        await refreshStates()
    }

    func refreshStates() async {
        let snapshot = await services.relayPool.statusSnapshot()
        var states: [URL: RelayState] = [:]
        for entry in snapshot {
            states[entry.url] = entry.state
        }
        relayStates = states
    }

    private func truncated(_ npub: String) -> String {
        guard npub.count > 20 else { return npub }
        return "\(npub.prefix(12))…\(npub.suffix(6))"
    }
}
