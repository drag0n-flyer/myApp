import AppCore
import AVFoundation
import Foundation
import Messaging
import Observation

/// Plays voice messages: one at a time, one instance per chat screen. The files are Ogg Opus, decoded by
/// `VoicePlaybackLoader` (in `Messaging`) into PCM off the main actor and played with `AVAudioEngine`.
@MainActor @Observable
final class VoicePlayer {
    /// The message that is playing or paused.
    private(set) var playingMessageID: String?
    private(set) var isPaused = false
    /// 0...1 through the current message.
    private(set) var progress: Double = 0
    /// A message that could not be decoded; the bubble says "Couldn't download".
    private(set) var failedMessageIDs: Set<String> = []

    private var selection = VoiceSelection()
    private var engine: AVAudioEngine?
    private var node: AVAudioPlayerNode?
    private var sampleRate: Double = 1
    private var totalFrames: Double = 1
    private var generation = 0
    private var ticker: Task<Void, Never>?
    private var decoding: Task<Void, Never>?
    #if os(iOS)
    private var interruption: NSObjectProtocol?
    private var sessionActive = false
    #endif

    /// Play, pause or resume. A different message replaces the current one.
    /// `url` must already be known to be inside the app's media directory.
    func toggle(messageID: String, url: URL, isMine: Bool) {
        switch selection.toggle(messageID) {
        case .play: start(messageID: messageID, url: url, origin: isMine ? .local : .received)
        case .pause:
            node?.pause()
            isPaused = true
        case .resume:
            node?.play()
            isPaused = false
        }
        playingMessageID = selection.playingID
    }

    func isPlaying(_ messageID: String) -> Bool { playingMessageID == messageID && !isPaused }

    /// Stops playback and hands the audio session back. Safe to call at any time.
    func stop() {
        generation += 1
        decoding?.cancel()
        decoding = nil
        ticker?.cancel()
        ticker = nil
        node?.stop()
        engine?.stop()
        node = nil
        engine = nil
        selection.stop()
        playingMessageID = nil
        isPaused = false
        progress = 0
        endSession()
    }

    // MARK: - Playback

    private func start(messageID: String, url: URL, origin: VoiceNoteOrigin) {
        // Replace whatever was playing, without letting go of the selection that was just made.
        generation += 1
        let mine = generation
        decoding?.cancel()
        ticker?.cancel()
        node?.stop()
        engine?.stop()
        node = nil
        engine = nil
        progress = 0
        isPaused = false
        failedMessageIDs.remove(messageID)

        decoding = Task { [weak self] in
            let loaded: LoadedVoice? = await Task.detached(priority: .userInitiated) {
                (try? VoicePlaybackLoader.load(url, origin: origin)).map(LoadedVoice.init)
            }.value
            guard let self, !Task.isCancelled, mine == self.generation else { return }
            guard let loaded else { self.fail(messageID); return }
            self.play(loaded.buffer, messageID: messageID, generation: mine)
        }
    }

    private func play(_ buffer: AVAudioPCMBuffer, messageID: String, generation mine: Int) {
        guard buffer.frameLength > 0 else { fail(messageID); return }
        beginSession()
        let engine = AVAudioEngine()
        let node = AVAudioPlayerNode()
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: buffer.format)
        do { try engine.start() } catch { fail(messageID); return }
        sampleRate = buffer.format.sampleRate
        totalFrames = Double(buffer.frameLength)
        self.engine = engine
        self.node = node
        node.scheduleBuffer(buffer, at: nil, options: [], completionCallbackType: .dataPlayedBack) { [weak self] _ in
            Task { @MainActor in self?.ended(generation: mine, messageID: messageID) }
        }
        node.play()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                self?.tick(generation: mine)
            }
        }
    }

    private func tick(generation mine: Int) {
        guard mine == generation, !isPaused, let node,
              let render = node.lastRenderTime, let time = node.playerTime(forNodeTime: render) else { return }
        progress = VoiceSelection.clamp(Double(time.sampleTime) / totalFrames)
    }

    private func ended(generation mine: Int, messageID: String) {
        guard mine == generation else { return }
        stop()
    }

    private func fail(_ messageID: String) {
        failedMessageIDs.insert(messageID)
        stop()
    }

    // MARK: - Audio session (iPhone)

    /// Playback category: other audio is paused while a voice message plays, and handed back afterwards.
    private func beginSession() {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
        sessionActive = true
        if let interruption { NotificationCenter.default.removeObserver(interruption) }
        interruption = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.stop() }
        }
        #endif
    }

    private func endSession() {
        #if os(iOS)
        if let interruption { NotificationCenter.default.removeObserver(interruption) }
        interruption = nil
        // Only give the session back when this player took it (a call may own it otherwise).
        guard sessionActive else { return }
        sessionActive = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}

/// A decoded voice note moved between actors; it is only read after the move.
private struct LoadedVoice: @unchecked Sendable {
    let buffer: AVAudioPCMBuffer
}
