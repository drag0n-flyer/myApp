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

    /// True while a call is on. Set by the chat screen from the call engine's state; a voice message then does
    /// not play, and the audio session is never touched (a call owns it).
    var callActive = false
    /// Asked at the moment of a tap, so a call that started a moment ago is seen even before `callActive` updates.
    var isInCall: () -> Bool = { false }

    private var selection = VoiceSelection()
    private var engine: AVAudioEngine?
    private var node: AVAudioPlayerNode?
    private var source: VoiceChunkSource?
    private var playingID: String?
    private var inFlight = 0
    private var feeding = false
    private var sourceDone = false
    private var started = false
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
        guard VoicePlaybackPolicy.canPlay(isInCall: callActive || isInCall()) else { return }
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

    /// A call started or ended: playback in progress stops when one starts.
    func callStateChanged(inCall: Bool) {
        callActive = inCall
        if VoicePlaybackPolicy.shouldStop(isInCall: inCall, isPlaying: playingMessageID != nil) { stop() }
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
        source = nil
        playingID = nil
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
        source = nil
        progress = 0
        isPaused = false
        failedMessageIDs.remove(messageID)

        decoding = Task { [weak self] in
            // Only the file's header and packets are read here; the audio is decoded a chunk at a time (`fill`).
            let opened: OpenedVoice? = await Task.detached(priority: .userInitiated) {
                (try? VoiceChunkSource.open(url, origin: origin)).map(OpenedVoice.init)
            }.value
            guard let self, !Task.isCancelled, mine == self.generation else { return }
            guard let opened else { self.fail(messageID); return }
            self.begin(opened.source, messageID: messageID, generation: mine)
        }
    }

    private func begin(_ source: VoiceChunkSource, messageID: String, generation mine: Int) {
        beginSession()
        let engine = AVAudioEngine()
        let node = AVAudioPlayerNode()
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: source.format)
        do { try engine.start() } catch { fail(messageID); return }
        totalFrames = Double(max(source.totalFrames, 1))
        self.engine = engine
        self.node = node
        self.source = source
        self.playingID = messageID
        inFlight = 0
        feeding = false
        sourceDone = false
        started = false
        fill(generation: mine)
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                self?.tick(generation: mine)
            }
        }
    }

    /// Keeps up to three chunks (about 10 seconds each) decoded and scheduled ahead of the playhead.
    private func fill(generation mine: Int) {
        guard mine == generation, !feeding, !sourceDone, inFlight < 3, let source else { return }
        feeding = true
        Task.detached(priority: .userInitiated) { [weak self] in
            let chunk: DecodedChunk
            do { chunk = DecodedChunk(buffer: try source.next(), failed: false) } catch { chunk = DecodedChunk(buffer: nil, failed: true) }
            await self?.chunkDecoded(chunk, generation: mine)
        }
    }

    private func chunkDecoded(_ chunk: DecodedChunk, generation mine: Int) {
        guard mine == generation, let node else { return }
        feeding = false
        if chunk.failed { if let id = playingID { fail(id) }; return }
        guard let buffer = chunk.buffer else {
            sourceDone = true
            if inFlight == 0 { stop() }
            return
        }
        inFlight += 1
        node.scheduleBuffer(buffer, at: nil, options: [], completionCallbackType: .dataPlayedBack) { [weak self] _ in
            Task { @MainActor in self?.chunkPlayed(generation: mine) }
        }
        if !started {
            started = true
            if !isPaused { node.play() }   // paused before the first chunk was ready: play() comes with "resume"
        }
        fill(generation: mine)
    }

    private func chunkPlayed(generation mine: Int) {
        guard mine == generation else { return }
        inFlight -= 1
        if sourceDone && inFlight == 0 { stop() } else { fill(generation: mine) }
    }

    private func tick(generation mine: Int) {
        // The player node counts frames across all the chunks scheduled on it, so this is the position in the note.
        guard mine == generation, !isPaused, let node,
              let render = node.lastRenderTime, let time = node.playerTime(forNodeTime: render) else { return }
        progress = VoiceSelection.clamp(Double(time.sampleTime) / totalFrames)
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

/// A chunk source moved between actors; it is only used by one task at a time.
private struct OpenedVoice: @unchecked Sendable {
    let source: VoiceChunkSource
}

/// One decoded chunk, or the end (`buffer == nil`), or a failure.
private struct DecodedChunk: @unchecked Sendable {
    let buffer: AVAudioPCMBuffer?
    let failed: Bool
}
