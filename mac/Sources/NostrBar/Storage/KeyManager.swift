import Foundation

/// Loads, stores, and uses the Nostr identity. The private key lives in the
/// Keychain at rest and in memory only while the app runs. It is never logged.
actor KeyManager {
    enum ImportError: Error {
        case invalidKey(String)
        case keychainFailure
    }

    private let keychain: KeychainStore
    private(set) var identity: NostrIdentity?

    init(keychain: KeychainStore = KeychainStore(service: AppConstants.keychainService)) {
        self.keychain = keychain
    }

    /// Loads a stored identity if one exists.
    func loadStoredIdentity() {
        guard identity == nil,
              let data = keychain.get(AppConstants.keychainAccount),
              let stored = try? Keys.identity(from: data)
        else { return }
        identity = stored
    }

    var hasIdentity: Bool {
        identity != nil
    }

    /// Validates and stores a new key (`nsec…` or hex). Returns the derived npub.
    @discardableResult
    func importKey(_ input: String) throws -> String {
        let data: Data
        do {
            data = try Keys.parsePrivateKey(input)
        } catch let error as KeyError {
            throw ImportError.invalidKey(describe(error))
        } catch {
            throw ImportError.invalidKey("Couldn't read this key.")
        }
        guard let newIdentity = try? Keys.identity(from: data) else {
            throw ImportError.invalidKey("This key can't be used.")
        }
        do {
            try keychain.set(data, account: AppConstants.keychainAccount)
        } catch {
            throw ImportError.keychainFailure
        }
        identity = newIdentity
        return newIdentity.npub
    }

    func removeKey() {
        keychain.remove(AppConstants.keychainAccount)
        identity = nil
    }

    /// Builds and signs a fresh kind-1 note. Loads the stored identity on demand
    /// so the composer never waits on Keychain at open time.
    func signNote(content: String, now: Date = Date()) throws -> NostrEvent {
        if identity == nil {
            loadStoredIdentity()
        }
        guard let identity else { throw KeyError.derivationFailed }
        return try NostrEventFactory.signedNote(content: content, identity: identity, now: now)
    }

    private func describe(_ error: KeyError) -> String {
        switch error {
        case .empty: "Paste your private key first."
        case .invalidNsec: "That doesn't look like an nsec key."
        case .invalidHex, .invalidLength: "A private key is nsec1… or 64 hex characters."
        case .invalidScalar, .derivationFailed: "This key can't be used."
        }
    }
}
