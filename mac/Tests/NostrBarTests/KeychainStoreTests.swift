import Foundation
import Testing

@testable import NostrBar

struct KeychainStoreTests {
    private func makeStore() -> KeychainStore {
        // Unique service per run keeps tests isolated from the app's real item.
        KeychainStore(service: "app.nostrbar.tests.\(UUID().uuidString)")
    }

    @Test func roundTripsAndUpdatesAndDeletes() throws {
        let store = makeStore()
        defer { store.remove("account") }

        #expect(store.get("account") == nil)

        try store.set(Data("first".utf8), account: "account")
        #expect(store.get("account") == Data("first".utf8))

        try store.set(Data("second".utf8), account: "account")
        #expect(store.get("account") == Data("second".utf8))

        store.remove("account")
        #expect(store.get("account") == nil)
    }
}

/// KeyManager behavior with throwaway keychain services.
@MainActor
struct KeyManagerTests {
    @Test func startsEmptyThenLoadsAfterImport() async throws {
        let service = "app.nostrbar.tests.km.\(UUID().uuidString)"
        let manager = KeyManager(keychain: KeychainStore(service: service))
        await manager.loadStoredIdentity()
        #expect(await !manager.hasIdentity)

        _ = try await manager.importKey(String(repeating: "ab", count: 32))
        #expect(await manager.hasIdentity)

        // Same service, fresh manager: simulates an app relaunch.
        let relaunched = KeyManager(keychain: KeychainStore(service: service))
        await relaunched.loadStoredIdentity()
        #expect(await relaunched.hasIdentity)
    }

    @Test func importRejectsGarbage() async {
        let manager = KeyManager(keychain: KeychainStore(service: "app.nostrbar.tests.km.\(UUID().uuidString)"))
        await #expect(throws: KeyManager.ImportError.self) { try await manager.importKey("garbage") }
        await #expect(throws: KeyManager.ImportError.self) { try await manager.importKey("") }
    }

    @Test func signsFreshEvents() async throws {
        let manager = KeyManager(keychain: KeychainStore(service: "app.nostrbar.tests.km.\(UUID().uuidString)"))
        _ = try await manager.importKey(String(repeating: "ef", count: 32))
        let event1 = try await manager.signNote(content: "first")
        let event2 = try await manager.signNote(content: "second")
        #expect(event1.id != event2.id)
        #expect(event1.content == "first")
        #expect(NostrEventFactory.internallyValid(event1))
        #expect(NostrEventFactory.internallyValid(event2))
    }
}
