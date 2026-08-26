import Foundation
import Testing

@testable import NostrBar

struct Nip19Tests {
    @Test func encodesAndDecodesNevent() throws {
        let id = "ded1a6e23b8c36fc6d4c6a4f4c61302639dd40062978efade0a0b00a21761932"
        let author = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

        let encoded = try #require(Nip19.nevent(eventIDHex: id, authorHex: author, kind: 1))
        #expect(encoded.hasPrefix("nevent1"))

        let decoded = try #require(Nip19.decodeNevent(encoded))
        #expect(hexEncode(decoded.id) == id)
        #expect(decoded.author.map(hexEncode) == author)
        #expect(decoded.kind == 1)
    }

    @Test func rejectsMalformedInput() {
        #expect(Nip19.nevent(eventIDHex: "zz", authorHex: "aa", kind: 1) == nil)
        #expect(Nip19.decodeNevent("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6") == nil)
    }
}

struct DraftRestoreTests {
    @Test func emptyEditorGetsPendingText() {
        #expect(DraftRestore.merge(pending: "lost note", current: "") == "lost note")
        #expect(DraftRestore.merge(pending: "lost note", current: "   \n  ") == "lost note")
    }

    @Test func typedTextIsPreservedBelow() {
        #expect(DraftRestore.merge(pending: "lost note", current: "new thought") == "lost note\n\nnew thought")
    }
}

struct SoundTests {
    @Test func producesValidWavData() throws {
        for sound in [AppSound.success, .failure] {
            let data = try #require(AppSound.wavData(for: sound))
            // RIFF/WAVE headers present and plausible length.
            #expect(data.count > 100)
            #expect(String(data: data.prefix(4), encoding: .ascii) == "RIFF")
            #expect(String(data: data.subdata(in: 8..<12), encoding: .ascii) == "WAVE")
        }
    }
}
