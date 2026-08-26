import Foundation
import Security
import Testing

@testable import NostrBar

struct KeysTests {
    private let nsecVector = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private let hexVector = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"

    @Test func parsesNsec() throws {
        let data = try Keys.parsePrivateKey(nsecVector)
        #expect(hexEncode(data) == hexVector)
    }

    @Test func parsesHex() throws {
        let data = try Keys.parsePrivateKey(hexVector)
        #expect(data.count == 32)
    }

    @Test func rejectsInvalidInput() {
        #expect(throws: KeyError.empty) { try Keys.parsePrivateKey("   ") }
        #expect(throws: KeyError.invalidLength) { try Keys.parsePrivateKey("abc123") }
        #expect(throws: KeyError.invalidHex) { try Keys.parsePrivateKey(String(repeating: "zz", count: 32)) }
        #expect(throws: KeyError.invalidNsec) { try Keys.parsePrivateKey("nsec1short") }
        // npub is not a private key.
        #expect(throws: KeyError.invalidNsec) {
            try Keys.parsePrivateKey("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6")
        }
    }

    /// BIP-340 vector 0 ties our derivation to the reference implementation:
    /// secret 0x…03 has x-only public key F9308A01…
    @Test func derivesKnownPublicKey() throws {
        let secret = Data(repeating: 0, count: 31) + [0x03]
        let derived = try #require(Keys.derivePublicKeyHex(from: secret))
        #expect(derived == "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9")
    }

    @Test func generatedIdentityIsSelfConsistent() throws {
        let identity = try Keys.generateIdentity()
        #expect(identity.publicKeyHex.count == 64)
        // The npub must encode the same public key.
        let decoded = try Bech32.decode(identity.npub)
        #expect(decoded.hrp == "npub")
        #expect(hexEncode(decoded.data) == identity.publicKeyHex)
    }

    @Test func nsecEncodingRoundTrips() throws {
        let data = try Keys.parsePrivateKey(nsecVector)
        #expect(try Keys.nsec(from: data) == nsecVector)
    }
}
