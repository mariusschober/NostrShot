import Foundation

/// NIP-19 TLV entities. Only `nevent` is needed by this app.
enum Nip19 {
    /// Encodes an event reference: `nevent1…` (id + author + kind).
    static func nevent(eventIDHex: String, authorHex: String, kind: Int) -> String? {
        guard let id = Data(hexString: eventIDHex), id.count == 32,
              let author = Data(hexString: authorHex), author.count == 32
        else { return nil }

        var tlv: [UInt8] = []
        tlv.append(0) // type: special (event id)
        tlv.append(32)
        tlv.append(contentsOf: Array(id))
        tlv.append(2) // type: author
        tlv.append(32)
        tlv.append(contentsOf: Array(author))
        tlv.append(3) // type: kind
        tlv.append(4)
        tlv.append(UInt8((kind >> 24) & 0xff))
        tlv.append(UInt8((kind >> 16) & 0xff))
        tlv.append(UInt8((kind >> 8) & 0xff))
        tlv.append(UInt8(kind & 0xff))

        return try? Bech32.encode(hrp: "nevent", data: tlv)
    }

    /// Decodes a `nevent1…` string back into its TLV fields (used by tests).
    static func decodeNevent(_ input: String) -> (id: [UInt8], author: [UInt8]?, kind: Int?)? {
        guard let decoded = try? Bech32.decode(input), decoded.hrp == "nevent" else { return nil }
        let bytes = decoded.data
        var id: [UInt8]?
        var author: [UInt8]?
        var kind: Int?

        var index = 0
        while index + 2 <= bytes.count {
            let type = bytes[index]
            let length = Int(bytes[index + 1])
            guard index + 2 + length <= bytes.count else { return nil }
            let value = Array(bytes[(index + 2)..<(index + 2 + length)])
            switch type {
            case 0 where length == 32: id = value
            case 2 where length == 32: author = value
            case 3 where length == 4:
                kind = (Int(value[0]) << 24) | (Int(value[1]) << 16) | (Int(value[2]) << 8) | Int(value[3])
            default:
                break // ignore unknown TLVs per NIP-19
            }
            index += 2 + length
        }
        guard let id, id.count == 32 else { return nil }
        return (id, author, kind)
    }
}
