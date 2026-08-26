import AppKit
import Foundation

/// Synthesized, tiny confirmation sounds. No audio assets; PCM is generated
/// in memory so the app stays a couple of megabytes.
enum AppSound {
    case success
    case failure

    private static let sampleRate = 44_100.0

    static func play(_ sound: AppSound, enabled: Bool) {
        guard enabled, let data = wavData(for: sound) else { return }
        NSSound(data: data)?.play()
    }

    // MARK: - Synthesis

    private static func samples(for sound: AppSound) -> [Float] {
        switch sound {
        case .success:
            // Two quick soft blips: a gentle upward "done" gesture.
            var out = blip(frequency: 740, start: 0, duration: 0.055, gain: 0.16)
            out += blip(frequency: 1108, start: 0.055, duration: 0.09, gain: 0.14)
            return out
        case .failure:
            return blip(frequency: 220, start: 0, duration: 0.11, gain: 0.10)
        }
    }

    /// Sine with exponential decay, faded in over 3 ms to avoid clicks.
    private static func blip(frequency: Double, start: Double, duration: Double, gain: Double) -> [Float] {
        let leadIn = Int(start * sampleRate)
        let count = Int(duration * sampleRate)
        var out = [Float](repeating: 0, count: leadIn)
        out.reserveCapacity(leadIn + count)
        for i in 0..<count {
            let t = Double(i) / sampleRate
            let envelope = min(t / 0.003, 1) * exp(-t * 18)
            out.append(Float(gain * envelope * sin(2 * .pi * frequency * t)))
        }
        return out
    }

    static func wavData(for sound: AppSound) -> Data? {
        let samples = samples(for: sound)
        guard !samples.isEmpty else { return nil }

        let pcm = samples.map { Int16(max(-1, min(1, Double($0))) * 32_767) }
        let dataSize = pcm.count * 2
        var data = Data(capacity: 44 + dataSize)

        func append(_ string: String) { data.append(contentsOf: string.utf8) }
        func append(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        func append(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }

        append("RIFF")
        append(UInt32(36 + dataSize))
        append("WAVE")
        append("fmt ")
        append(UInt32(16))
        append(UInt16(1)) // PCM
        append(UInt16(1)) // mono
        append(UInt32(sampleRate))
        append(UInt32(Int(sampleRate) * 2)) // byte rate
        append(UInt16(2)) // block align
        append(UInt16(16)) // bits
        append("data")
        append(UInt32(dataSize))
        pcm.withUnsafeBufferPointer { buffer in
            guard let base = buffer.baseAddress else { return }
            data.append(contentsOf: UnsafeRawBufferPointer(start: base, count: dataSize))
        }
        return data
    }
}
