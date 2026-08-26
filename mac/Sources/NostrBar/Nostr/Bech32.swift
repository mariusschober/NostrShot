import Foundation

/// Minimal bech32 (BIP-173, not bech32m) encoder/decoder used by NIP-19.
enum Bech32 {
    enum Bech32Error: Error, Equatable {
        case invalidCharacter(Character)
        case invalidChecksum
        case invalidLength
        case mixedCase
        case missingSeparator
        case invalidPadding
    }

    static let charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")

    private static let generator: [UInt32] = [0x3b6a_57b2, 0x2650_8e6d, 0x1ea1_19fa, 0x3d42_33dd, 0x2a14_62b3]

    /// Encodes a payload with the given human-readable part.
    static func encode(hrp: String, data: [UInt8]) throws -> String {
        let converted = convertBits(data, fromBits: 8, toBits: 5, pad: true)
        return try encode5bit(hrp: hrp, values: converted)
    }

    /// Decodes a bech32 string into its human-readable part and 8-bit payload.
    static func decode(_ input: String) throws -> (hrp: String, data: [UInt8]) {
        guard input.count >= 8, input.count <= 5000 else { throw Bech32Error.invalidLength }
        guard input == input.lowercased() || input == input.uppercased() else { throw Bech32Error.mixedCase }
        let string = input.lowercased()
        guard let separatorIndex = string.lastIndex(of: "1") else { throw Bech32Error.missingSeparator }
        let hrp = String(string[string.startIndex..<separatorIndex])
        guard !hrp.isEmpty else { throw Bech32Error.invalidLength }
        let dataPart = string[string.index(after: separatorIndex)...]
        guard dataPart.count >= 6 else { throw Bech32Error.invalidLength }
        var values: [UInt8] = []
        values.reserveCapacity(dataPart.count)
        for char in dataPart {
            guard let value = charset.firstIndex(of: char) else { throw Bech32Error.invalidCharacter(char) }
            values.append(UInt8(value))
        }
        guard polymod(hrp: Array(hrp.utf8), values: values) == 1 else { throw Bech32Error.invalidChecksum }
        let payload = Array(values.dropLast(6))
        let converted = convertBits(payload, fromBits: 5, toBits: 8, pad: false)
        guard !converted.isEmpty else { throw Bech32Error.invalidPadding }
        return (hrp, converted)
    }

    // MARK: - Internals

    private static func encode5bit(hrp: String, values: [UInt8]) throws -> String {
        // Avoid `values + [0,0,0,0,0,0]` copy by appending virtually in polymod call.
        let mod = polymod(hrp: Array(hrp.utf8), values: values + [0, 0, 0, 0, 0, 0]) ^ 1
        let checksum = (0..<6).map { UInt8((mod >> (5 * (5 - $0))) & 31) }

        var output = hrp + "1"
        output.reserveCapacity(hrp.count + 1 + values.count + 6)
        for value in values {
            guard Int(value) < charset.count else { throw Bech32Error.invalidPadding }
            output.append(charset[Int(value)])
        }
        for value in checksum {
            guard Int(value) < charset.count else { throw Bech32Error.invalidPadding }
            output.append(charset[Int(value)])
        }
        return output
    }

    private static func polymod(hrp: [UInt8], values: [UInt8]) -> UInt32 {
        var chk: UInt32 = 1
        for byte in hrp {
            chk = step(chk: chk, value: UInt32(byte >> 5))
        }
        chk = step(chk: chk, value: 0)
        for byte in hrp {
            chk = step(chk: chk, value: UInt32(byte & 31))
        }
        for value in values {
            chk = step(chk: chk, value: UInt32(value))
        }
        return chk
    }

    private static func step(chk initial: UInt32, value: UInt32) -> UInt32 {
        var chk = initial
        let top = chk >> 25
        chk = ((chk & 0x1ff_ffff) << 5) ^ value
        for i in 0..<5 where (top >> UInt32(i)) & 1 == 1 {
            chk ^= generator[i]
        }
        return chk
    }

    /// Regroups a byte array between 5-bit and 8-bit alignments.
    private static func convertBits(_ data: [UInt8], fromBits: Int, toBits: Int, pad: Bool) -> [UInt8] {
        var accumulator: Int = 0
        var bits: Int = 0
        // Pre-reserve to avoid reallocations for 32-byte payloads (npub/nsec).
        var output: [UInt8] = []
        output.reserveCapacity((data.count * fromBits + toBits - 1) / toBits)
        let maxOutputValue = (1 << toBits) - 1
        for byte in data {
            accumulator = (accumulator << fromBits) | Int(byte)
            bits += fromBits
            while bits >= toBits {
                bits -= toBits
                output.append(UInt8((accumulator >> bits) & maxOutputValue))
            }
        }
        if pad {
            if bits > 0 {
                output.append(UInt8((accumulator << (toBits - bits)) & maxOutputValue))
            }
        } else if bits >= fromBits || ((accumulator << (toBits - bits)) & maxOutputValue) != 0 {
            return []
        }
        return output
    }
}
