import CryptoKit
import Foundation
import P256K
import Foundation
import Testing

@testable import NostrBar

struct EventTests {
    /// Official BIP-340 test vectors (github.com/bitcoin/bips bip-0340/test-vectors.csv).
    private let bip340Vectors: [(secret: String, aux: String, message: String, signature: String)] = [
        (
            "0000000000000000000000000000000000000000000000000000000000000003",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"
        ),
        (
            "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            "0000000000000000000000000000000000000000000000000000000000000001",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A"
        ),
    ]

    @Test func producesOfficialBIP340Signatures() throws {
        for vector in bip340Vectors {
            let secret = try #require(Data(hexString: vector.secret))
            let key = try P256K.Schnorr.PrivateKey(dataRepresentation: secret)
            let message = try #require(Data(hexString: vector.message))
            let aux = try #require(Data(hexString: vector.aux))

            // BIP-340 signs the raw 32-byte message; HashDigest wraps those exact bytes.
            let digest = SHA256Digest(Array(message))
            let signature = try key.signature(for: digest, auxiliaryRand: Array(aux))
            #expect(hexEncode(signature.dataRepresentation).lowercased() == vector.signature.lowercased())
        }
    }

    @Test func canonicalSerializationFollowsNIP01() throws {
        let event = NostrEvent(
            id: "",
            pubkey: "33fe35148873ab72abb125be1fbecdfcc76fbe0e4f68ad930efaa1b928ce24ae",
            createdAt: 1_687_374_919,
            kind: 1,
            tags: [],
            content: "Line one\n\"quoted\" \\ backslash\ttab ☀️ emoji stays verbatim",
            signature: ""
        )
        let expected = "[0,\"33fe35148873ab72abb125be1fbecdfcc76fbe0e4f68ad930efaa1b928ce24ae\",1687374919,1,[]," +
            "\"Line one\\n\\\"quoted\\\" \\\\ backslash\\ttab ☀️ emoji stays verbatim\"]"
        #expect(NostrEventFactory.serializedForID(event) == expected)
    }

    /// Independent hash check: the event id must equal CryptoKit's SHA-256 of the
    /// canonical serialization.
    @Test func eventIDMatchesIndependentSHA256() throws {
        let identity = try Keys.generateIdentity()
        let event = NostrEvent(
            id: "",
            pubkey: identity.publicKeyHex,
            createdAt: 1_700_000_000,
            kind: 1,
            tags: [["t", "nostr"]],
            content: "independent hash test",
            signature: ""
        )
        let serialized = NostrEventFactory.serializedForID(event)
        let reference = SHA256.hash(data: Data(serialized.utf8))
        #expect(NostrEventFactory.computeID(event) == reference.map { String(format: "%02x", $0) }.joined())
    }

    @Test func signedNoteIsValid() throws {
        let identity = try Keys.generateIdentity()
        let event = try NostrEventFactory.signedNote(content: "Simple tools create different behavior than complex ones.", identity: identity)

        #expect(event.kind == 1)
        #expect(event.tags == [])
        #expect(event.content == "Simple tools create different behavior than complex ones.")
        #expect(event.pubkey == identity.publicKeyHex)
        #expect(event.id.count == 64)
        #expect(event.signature.count == 128)
        #expect(event.id.allSatisfy { $0.isHexDigit })
        #expect(event.signature.allSatisfy { $0.isHexDigit })

        // Internally consistent: id recomputation + BIP-340 verification.
        #expect(NostrEventFactory.internallyValid(event))
    }

    @Test func tamperedContentBreaksValidity() throws {
        let identity = try Keys.generateIdentity()
        var event = try NostrEventFactory.signedNote(content: "honest content", identity: identity)
        event.content = "tampered"
        #expect(!NostrEventFactory.internallyValid(event))
    }

    @Test func relayMessageShape() throws {
        let identity = try Keys.generateIdentity()
        let event = try NostrEventFactory.signedNote(content: "hello", identity: identity)
        let message = NostrEventFactory.relayMessage(event)
        #expect(message.hasPrefix("[\"EVENT\","))
        #expect(message.hasSuffix("}]"))

        // The embedded JSON must parse and contain the exact fields we set.
        let jsonStart = message.index(message.startIndex, offsetBy: 9)
        let payload = String(message[jsonStart..<message.index(before: message.endIndex)])
        let parsed = try #require(JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any])
        #expect(parsed["id"] as? String == event.id)
        #expect(parsed["pubkey"] as? String == event.pubkey)
        #expect(parsed["kind"] as? Int == 1)
        #expect(parsed["content"] as? String == "hello")
        #expect(parsed["sig"] as? String == event.signature)
        #expect((parsed["created_at"] as? Int) == event.createdAt)
        #expect((parsed["tags"] as? [[String]]) == [])
    }
}
