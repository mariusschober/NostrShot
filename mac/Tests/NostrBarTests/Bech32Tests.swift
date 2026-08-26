import Foundation
import Security
import Testing

@testable import NostrBar

struct Bech32Tests {
    /// NIP-19 example vectors.
    @Test func decodesKnownVectors() throws {
        let nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        let decoded = try Bech32.decode(nsec)
        #expect(decoded.hrp == "nsec")
        #expect(hexEncode(decoded.data) == "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa")

        let npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
        let decodedPub = try Bech32.decode(npub)
        #expect(decodedPub.hrp == "npub")
        #expect(hexEncode(decodedPub.data) == "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e")
    }

    @Test func encodesKnownVectors() throws {
        let encoded = try Bech32.encode(
            hrp: "npub",
            data: Array(Data(hexString: "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d")!)
        )
        #expect(encoded == "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6")
    }

    @Test func rejectsCorruptedChecksum() {
        var corrupted = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        corrupted = String(corrupted.dropLast()) + (corrupted.hasSuffix("5") ? "6" : "5")
        #expect(throws: Bech32.Bech32Error.invalidChecksum) { try Bech32.decode(corrupted) }
    }

    @Test func rejectsMixedCase() {
        #expect(throws: Bech32.Bech32Error.mixedCase) {
            try Bech32.decode("Nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5")
        }
    }

    @Test func roundTripsRandomPayload() throws {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, 32, &bytes)
        #expect(status == errSecSuccess)
        let encoded = try Bech32.encode(hrp: "nsec", data: bytes)
        let decoded = try Bech32.decode(encoded)
        #expect(decoded.hrp == "nsec")
        #expect(decoded.data == bytes)
    }
}
