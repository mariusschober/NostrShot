import Foundation
import P256K

/// A signed Nostr event (NIP-01). All fields are lowercase-hex where applicable.
struct NostrEvent: Equatable, Sendable {
    var id: String
    var pubkey: String
    var createdAt: Int
    var kind: Int
    var tags: [[String]]
    var content: String
    var signature: String
}

enum EventError: Error {
    case signingFailed
    case invalidKeyMaterial
}

enum NostrEventFactory {
    /// Builds and signs a plain text note (kind 1) from `content`.
    static func signedNote(content: String, identity: NostrIdentity, now: Date = Date()) throws -> NostrEvent {
        let event = NostrEvent(
            id: "",
            pubkey: identity.publicKeyHex,
            createdAt: Int(now.timeIntervalSince1970),
            kind: 1,
            tags: [],
            content: content,
            signature: ""
        )
        return try sign(event, privateKey: identity.privateKey)
    }

    /// Computes the canonical NIP-01 serialization of the pre-signature fields.
    static func serializedForID(_ event: NostrEvent) -> String {
        "[0,\"\(escape(event.pubkey))\",\(event.createdAt),\(event.kind),\(serializeTags(event.tags)),\"\(escape(event.content))\"]"
    }

    /// SHA-256 event id over the canonical serialization.
    static func computeID(_ event: NostrEvent) -> String {
        let digest = SHA256.hash(data: Data(serializedForID(event).utf8))
        return hexEncode(digest.withUnsafeBytes { Array($0) })
    }

    /// Signs an event, filling in `id` and `signature`.
    static func sign(_ event: NostrEvent, privateKey: Data) throws -> NostrEvent {
        guard privateKey.count == 32 else { throw EventError.invalidKeyMaterial }
        guard let key = try? P256K.Schnorr.PrivateKey(dataRepresentation: privateKey) else {
            throw EventError.invalidKeyMaterial
        }

        var signed = event
        signed.id = computeID(signed)
        // BIP-340 signs the raw 32-byte event id; HashDigest wraps those exact bytes.
        let digest = SHA256Digest(Array(Data(hexString: signed.id) ?? Data()))
        guard let signature = try? key.signature(for: digest) else { throw EventError.signingFailed }
        signed.signature = hexEncode(signature.dataRepresentation)
        return signed
    }

    /// Verifies that a signed event is internally consistent (id + Schnorr signature).
    /// Used by tests to validate our own output against BIP-340 semantics.
    static func internallyValid(_ event: NostrEvent) -> Bool {
        guard event.id == computeID(withoutSignature: event) else { return false }
        guard let idBytes = Data(hexString: event.id), idBytes.count == 32,
              let sigBytes = Data(hexString: event.signature), sigBytes.count == 64,
              let pubBytes = Data(hexString: event.pubkey), pubBytes.count == 32
        else { return false }

        let xonly = P256K.Schnorr.XonlyKey(dataRepresentation: pubBytes)
        let digest = SHA256Digest(Array(idBytes))
        guard let signature = try? P256K.Schnorr.SchnorrSignature(dataRepresentation: sigBytes) else { return false }
        return xonly.isValidSignature(signature, for: digest)
    }

    static func computeID(withoutSignature event: NostrEvent) -> String {
        computeID(event)
    }

    /// The exact JSON array sent to relays: `["EVENT", {…}]`.
    static func relayMessage(_ event: NostrEvent) -> String {
        "[\"EVENT\",\(eventJSON(event))]"
    }

    /// Full event JSON on the wire. Field order follows NIP-01 examples.
    static func eventJSON(_ event: NostrEvent) -> String {
        """
        {"id":"\(event.id)","pubkey":"\(event.pubkey)","created_at":\(event.createdAt),"kind":\(event.kind),"tags":\(serializeTags(event.tags)),"content":"\(escape(event.content))","sig":"\(event.signature)"}
        """
    }

    // MARK: - Canonical serialization helpers

    /// Escapes exactly the characters NIP-01 requires; everything else passes through verbatim.
    static func escape(_ string: String) -> String {
        var out = String()
        out.reserveCapacity(string.count)
        for scalar in string.unicodeScalars {
            switch scalar {
            case "\u{08}": out += "\\b"
            case "\u{09}": out += "\\t"
            case "\u{0A}": out += "\\n"
            case "\u{0C}": out += "\\f"
            case "\u{0D}": out += "\\r"
            case "\u{22}": out += "\\\""
            case "\u{5C}": out += "\\\\"
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out
    }

    static func serializeTags(_ tags: [[String]]) -> String {
        let encoded = tags.map { tag in
            "[" + tag.map { "\"\(escape($0))\"" }.joined(separator: ",") + "]"
        }
        return "[" + encoded.joined(separator: ",") + "]"
    }
}
