import Foundation

/// User preferences persisted in UserDefaults. Never holds secret material.
struct Preferences {
    static let relaysKey = "relayURLs"
    static let draftKey = "draftText"
    static let draftCursorKey = "draftCursor"
    static let playSoundKey = "playSound"

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - Relays

    var relayURLs: [URL] {
        get {
            let strings = defaults.stringArray(forKey: Self.relaysKey)
            return (strings ?? AppConstants.defaultRelays)
                .compactMap(RelayURLNormalizer.normalize)
        }
        set {
            let strings = newValue.map(\.absoluteString)
            if strings.isEmpty {
                defaults.set(AppConstants.defaultRelays, forKey: Self.relaysKey)
            } else {
                defaults.set(strings, forKey: Self.relaysKey)
            }
        }
    }

    /// True once the user has customized (or explicitly reset) relays.
    var hasStoredRelays: Bool {
        defaults.stringArray(forKey: Self.relaysKey) != nil
    }

    // MARK: - Draft

    var draftText: String {
        get { defaults.string(forKey: Self.draftKey) ?? "" }
        set { defaults.set(newValue, forKey: Self.draftKey) }
    }

    func clearDraft() {
        defaults.removeObject(forKey: Self.draftKey)
        defaults.removeObject(forKey: Self.draftCursorKey)
    }

    // MARK: - Cursor

    /// Saved caret location for the draft; restored when the composer reopens.
    var draftCursor: Int {
        get { defaults.integer(forKey: Self.draftCursorKey) }
        set { defaults.set(newValue, forKey: Self.draftCursorKey) }
    }

    // MARK: - Sound

    var playSound: Bool {
        get { defaults.object(forKey: Self.playSoundKey) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Self.playSoundKey) }
    }
}
