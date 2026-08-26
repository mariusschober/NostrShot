import AVFoundation
import Foundation
@preconcurrency import Speech

/// On-device streaming dictation via Apple's Speech framework.
/// Streams partial transcripts into the composer while listening.
///
/// Concurrency note: Speech/AVFoundation invoke their callbacks on private
/// framework queues. Those closures are annotated `@Sendable` so they inherit
/// no actor isolation (inherited MainActor isolation traps at runtime when the
/// framework calls them off-main), and hop to the main actor explicitly.
@MainActor
final class DictationController: NSObject {
    private var engine: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private(set) var isListening = false
    private(set) var transcript = ""

    /// Called on the main actor with the running transcript after each update.
    var onUpdate: ((String) -> Void)?
    /// Called on the main actor when dictation can't start.
    var onUnavailable: ((String) -> Void)?

    /// Starts listening. Safe to call repeatedly; does nothing while active.
    func start() async {
        guard !isListening else { return }

        guard await Self.ensurePermissions() else {
            onUnavailable?("Allow microphone and speech access in System Settings to dictate.")
            return
        }
        let recognizer = SFSpeechRecognizer()
        guard let recognizer, recognizer.supportsOnDeviceRecognition else {
            onUnavailable?("On-device dictation isn't available for this language.")
            return
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true
        self.request = request

        let engine = AVAudioEngine()
        let input = engine.inputNode
        engine.prepare()
        do {
            try engine.start()
        } catch {
            onUnavailable?("Couldn't access the microphone.")
            return
        }
        // The input node reports its real hardware format only once the engine
        // is running; a zero format would crash installTap.
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            engine.stop()
            onUnavailable?("No microphone input available.")
            return
        }
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { @Sendable buffer, _ in
            request.append(buffer)
        }
        self.engine = engine

        task = recognizer.recognitionTask(with: request) { @Sendable [weak self] result, _ in
            let text = result?.bestTranscription.formattedString
            Task { @MainActor in
                guard let self, let text else { return }
                self.transcript = text
                self.onUpdate?(text)
            }
        }

        transcript = ""
        isListening = true
    }

    /// Stops listening and returns the transcript captured so far.
    @discardableResult
    func stop() -> String {
        guard isListening else { return transcript }
        let text = transcript
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        request?.endAudio()
        task?.finish()
        engine = nil
        request = nil
        task = nil
        isListening = false
        return text
    }

    // MARK: - Permissions

    private static func ensurePermissions() async -> Bool {
        let micGranted = await withCheckedContinuation { continuation in
            AVCaptureDevice.requestAccess(for: .audio) { @Sendable granted in
                continuation.resume(returning: granted)
            }
        }
        guard micGranted else { return false }

        return await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { @Sendable status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }
}
