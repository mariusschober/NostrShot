import Foundation
import Testing

@testable import NostrBar

/// Draft persistence semantics: typed text survives everything except success.
struct DraftTests {
    private func makePrefs() -> Preferences {
        Preferences(defaults: UserDefaults(suiteName: "nostrbar.tests.draft.\(UUID().uuidString)")!)
    }

    @Test func draftPersistsAcrossInstances() {
        var prefs = makePrefs()
        prefs.draftText = "unfinished thought"

        // A new instance reading the same defaults simulates an app relaunch.
        var reloaded = Preferences(defaults: prefs.defaults)
        #expect(reloaded.draftText == "unfinished thought")
    }

    @Test func escapePreservesDraft() {
        var prefs = makePrefs()
        prefs.draftText = "keep me"
        // Escape → panel hides without clearing.
        #expect(prefs.draftText == "keep me")
    }

    @Test func successfulPublishClearsDraft() {
        var prefs = makePrefs()
        prefs.draftText = "published text"
        prefs.clearDraft()
        #expect(prefs.draftText.isEmpty)
    }

    @Test func failedPublishKeepsDraft() {
        var prefs = makePrefs()
        prefs.draftText = "still here"
        // Failure path never calls clearDraft.
        #expect(prefs.draftText == "still here")
    }

    @Test func emptyDraftIsDefault() {
        #expect(makePrefs().draftText.isEmpty)
    }
}

struct RelayDefaultsTests {
    @Test func fallsBackToDefaults() {
        var prefs = Preferences(defaults: UserDefaults(suiteName: "nostrbar.tests.relays.\(UUID().uuidString)")!)
        #expect(!prefs.relayURLs.isEmpty)
        #expect(prefs.hasStoredRelays == false)

        // Custom list persists.
        let custom = [URL(string: "wss://custom.example.com")!]
        prefs.relayURLs = custom
        #expect(prefs.hasStoredRelays)
        var reloaded = Preferences(defaults: prefs.defaults)
        #expect(reloaded.relayURLs == custom)

        // Clearing restores defaults.
        reloaded.relayURLs = []
        let again = Preferences(defaults: prefs.defaults)
        #expect(again.relayURLs.map(\.absoluteString) == AppConstants.defaultRelays.compactMap(RelayURLNormalizer.normalize).map(\.absoluteString))
    }

    @Test func defaultRelaysAreWellFormed() {
        #expect(AppConstants.defaultRelays.count >= 3)
        for relay in AppConstants.defaultRelays {
            #expect(RelayURLNormalizer.normalize(relay) != nil, "invalid default relay: \(relay)")
        }
    }
}
