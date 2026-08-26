import Foundation
import Testing

@testable import NostrBar

struct RelayMessageTests {
    @Test func parsesAcceptOK() throws {
        let data = Data(#"["OK", "b1a649ebe8b6e9d7f2c", true, ""]"#.utf8)
        guard case .ok(let id, let accepted, let message)? = RelayMessage.parse(data) else {
            Issue.record("Expected .ok")
            return
        }
        #expect(id == "b1a649ebe8b6e9d7f2c")
        #expect(accepted)
        #expect(message.isEmpty)
    }

    @Test func parsesRejectionWithReason() {
        let data = Data(#"["OK", "abc", false, "blocked: you are banned from posting here"]"#.utf8)
        guard case .ok(_, false, let message)? = RelayMessage.parse(data) else {
            Issue.record("Expected .ok")
            return
        }
        #expect(message == "blocked: you are banned from posting here")
    }

    @Test func lowercasesEventID() {
        let data = Data(#"["OK", "ABCDEF0123", true, ""]"#.utf8)
        guard case .ok(let id, _, _)? = RelayMessage.parse(data) else {
            Issue.record("Expected .ok")
            return
        }
        #expect(id == "abcdef0123")
    }

    @Test func parsesNotice() {
        let data = Data(#"["NOTICE", "hello"]"#.utf8)
        #expect(RelayMessage.parse(data) == .notice("hello"))
    }

    @Test func ignoresOtherMessages() {
        #expect(RelayMessage.parse(Data(#"["EOSE", "sub1"]"#.utf8)) == .ignored)
        #expect(RelayMessage.parse(Data(#"["EVENT", "sub1", {"id":"x"}]"#.utf8)) == .ignored)
        #expect(RelayMessage.parse(Data(#"["CLOSED", "sub1", "error: db"]"#.utf8)) == .ignored)
    }

    @Test func rejectsMalformedInput() {
        #expect(RelayMessage.parse(Data("not json".utf8)) == nil)
        #expect(RelayMessage.parse(Data("[42]".utf8)) == nil)
        #expect(RelayMessage.parse(Data(#"["OK"]"#.utf8)) == nil)
        #expect(RelayMessage.parse(Data(#"["OK", "id", "not-a-bool", ""]"#.utf8)) == nil)
    }
}

struct RelayURLNormalizerTests {
    @Test func acceptsValidWSS() throws {
        let url = try #require(RelayURLNormalizer.normalize("wss://relay.example.com"))
        #expect(url.absoluteString == "wss://relay.example.com")
        #expect(RelayURLNormalizer.normalize("  WSS://Nos.Lol ")?.absoluteString == "wss://nos.lol")
        #expect(RelayURLNormalizer.normalize("wss://relay.example.com/path") != nil)
    }

    @Test func rejectsInvalidRelayURLs() {
        #expect(RelayURLNormalizer.normalize("") == nil)
        #expect(RelayURLNormalizer.normalize("https://relay.example.com") == nil)
        #expect(RelayURLNormalizer.normalize("ws://relay.example.com") == nil)
        #expect(RelayURLNormalizer.normalize("wss://") == nil)
        #expect(RelayURLNormalizer.normalize("relay.example.com") == nil)
    }
}
