import Foundation
import P256K

/// Errors raised while importing or deriving Nostr keys.
enum KeyError: Error, Equatable {
    case empty
    case invalidNsec
    case invalidHex
    case invalidLength
    case invalidScalar
    case derivationFailed
}

/// A Nostr identity: raw private key bytes plus the derived x-only public key.
struct NostrIdentity {
    let privateKey: Data
    let publicKeyHex: String

    /// `npub1…` encoding of the public key.
    var npub: String {
        (try? Bech32.encode(hrp: "npub", data: Array(Data(hexString: publicKeyHex) ?? Data()))) ?? publicKeyHex
    }
}

enum Keys {
    /// Parses a private key given either as `nsec1…` or as 64 lowercase/uppercase hex chars.
    static func parsePrivateKey(_ input: String) throws -> Data {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw KeyError.empty }

        let lowered = trimmed.lowercased()
        if lowered.hasPrefix("npub") || lowered.hasPrefix("note") {
            // A public entity, not a secret.
            throw KeyError.invalidNsec
        }

        let data: Data
        if lowered.hasPrefix("nsec") {
            let decoded = try? Bech32.decode(trimmed)
            guard let decoded, decoded.hrp == "nsec" else { throw KeyError.invalidNsec }
            data = Data(decoded.data)
        } else if trimmed.count == 64 {
            guard let hex = Data(hexString: trimmed) else { throw KeyError.invalidHex }
            data = hex
        } else {
            throw KeyError.invalidLength
        }

        guard data.count == 32 else { throw KeyError.invalidLength }
        // Reject scalars outside the valid secp256k1 range by attempting derivation.
        guard derivePublicKeyHex(from: data) != nil else { throw KeyError.invalidScalar }
        return data
    }

    /// Derives the 32-byte x-only public key for a secret key.
    static func derivePublicKey(from privateKey: Data) -> [UInt8]? {
        guard privateKey.count == 32 else { return nil }
        guard let key = try? P256K.Schnorr.PrivateKey(dataRepresentation: privateKey) else { return nil }
        return key.xonly.bytes
    }

    static func derivePublicKeyHex(from privateKey: Data) -> String? {
        derivePublicKey(from: privateKey).map(hexEncode)
    }

    /// Builds a full identity, throwing if the secret is unusable.
    static func identity(from privateKey: Data) throws -> NostrIdentity {
        guard let pub = derivePublicKeyHex(from: privateKey) else { throw KeyError.derivationFailed }
        return NostrIdentity(privateKey: privateKey, publicKeyHex: pub)
    }

    /// Generates a fresh random identity.
    static func generateIdentity() throws -> NostrIdentity {
        var attempts = 0
        while attempts < 8 {
            var bytes = [UInt8](repeating: 0, count: 32)
            let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            if status == errSecSuccess, let identity = try? identity(from: Data(bytes)) {
                return identity
            }
            attempts += 1
        }
        throw KeyError.derivationFailed
    }

    /// Encodes raw bytes as an `nsec1…` string. Used only in tests and never logged.
    static func nsec(from privateKey: Data) throws -> String {
        try Bech32.encode(hrp: "nsec", data: Array(privateKey))
    }
}

// MARK: - Hex

/// Fast hex encoding without `String(format:)` per byte (avoids temporary NSString
/// allocations observed in Allocations instrument during mass signing).
private let hexDigits: [UInt8] = Array("0123456789abcdef".utf8)

func hexEncode<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
    var chars: [UInt8] = []
    // Reserve capacity when possible to avoid reallocations.
    if let collection = bytes as? any Collection {
        chars.reserveCapacity(collection.count * 2)
    }
    for byte in bytes {
        chars.append(hexDigits[Int(byte >> 4)])
        chars.append(hexDigits[Int(byte & 0x0F)])
    }
    return String(bytes: chars, encoding: .utf8) ?? ""
}

extension Data {
    /// Strict hex decoding: even length, all characters hex.
    init?(hexString: String) {
        let characters = Array(hexString.lowercased().utf8)
        guard characters.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(characters.count / 2)

        func nibble(_ c: UInt8) -> UInt8? {
            switch c {
            case 0x30...0x39: return c - 0x30
            case 0x61...0x66: return c - 0x61 + 10
            default: return nil
            }
        }

        var index = 0
        while index < characters.count {
            guard let high = nibble(characters[index]), let low = nibble(characters[index + 1]) else {
                return nil
            }
            bytes.append(high << 4 | low)
            index += 2
        }
        self.init(bytes)
    }
}
