import Foundation
import Observation

public enum VoicePermission: Equatable, Sendable { case granted, denied, undetermined }

/// The microphone and recorder behind a voice note. The app's recorder and the sample-mode fake both conform.
@MainActor
public protocol VoiceCapture: AnyObject {
    var permission: VoicePermission { get }
    /// Asks the system; true when access is granted.
    func requestPermission() async -> Bool
    func start() throws
    /// Input level, 0...1.
    var level: Float { get }
    /// Seconds recorded so far.
    var elapsed: Double { get }
    /// Stops and returns the finished file and its length.
    func stop() throws -> (url: URL, duration: Double)
    /// Stops and deletes the file.
    func cancel()
    /// The recording just became locked (the finger is up).
    func recordingLocked()
}

extension VoiceCapture {
    public func recordingLocked() {}
}

/// Runs a voice note from the first touch to send, cancel or preview. `VoiceGesture` decides; this class
/// starts and stops the recorder, keeps the level meter and deletes files that are not sent.
@MainActor @Observable
public final class VoiceSession {
    public static let barCount = 30

    public private(set) var state: VoiceGesture.State = .idle
    /// Scrolling level meter, newest last, at most `barCount` values in 0...1.
    public private(set) var levels: [Float] = []
    public private(set) var elapsed: Double = 0
    /// "Hold to record, release to send" is showing.
    public private(set) var hintVisible = false
    public private(set) var denied = false
    public private(set) var previewURL: URL?
    public private(set) var previewDuration: Double = 0
    /// How far the finger has moved, for the slide hint and the lock glyph.
    public private(set) var dragOffset: (dx: Double, dy: Double) = (0, 0)

    private let capture: VoiceCapture
    public var rtl: Bool
    private let startDelay: Duration
    private let hintDuration: Duration
    private let maxDuration: Double
    public var onSend: (URL, Double) -> Void
    private var startTask: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var hintTask: Task<Void, Never>?

    public init(capture: VoiceCapture, rtl: Bool, startDelay: Duration = .milliseconds(VoiceGesture.startDelayMs),
                hintDuration: Duration = .seconds(2), maxDuration: Double = 1_800,
                onSend: @escaping (URL, Double) -> Void) {
        self.capture = capture
        self.rtl = rtl
        self.startDelay = startDelay
        self.hintDuration = hintDuration
        self.maxDuration = maxDuration
        self.onSend = onSend
    }

    /// True while a recording or preview is on screen.
    public var isActive: Bool { state != .idle }
    public var isRecording: Bool { [.recording, .willCancel, .willLock, .locked].contains(state) }

    // MARK: - Input

    /// The finger went down on the microphone.
    public func press() {
        guard state == .idle else { return }
        apply(.press)
        startTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: self.startDelay)
            guard !Task.isCancelled else { return }
            await self.beginRecording()
        }
    }

    /// The press has lasted `startDelay`: ask for the microphone and start. Called by the delayed task.
    public func beginRecording() async {
        guard state == .pending else { return }
        guard await ensurePermission() else { reset(); return }
        guard state == .pending else { return }
        do { try capture.start() } catch { reset(); return }
        levels = []
        elapsed = 0
        apply(.heldFor(ms: VoiceGesture.startDelayMs))
        startTicker()
    }

    public func drag(dx: Double, dy: Double) {
        dragOffset = (dx, dy)
        apply(.drag(dx: dx, dy: dy))
    }

    public func release() {
        startTask?.cancel()
        apply(.release(elapsedMs: Int(capture.elapsed * 1000)))
    }

    /// Mac, VoiceOver and anything without drag: start a locked recording at once.
    public func activateAccessible() async {
        guard state == .idle else { return }
        guard await ensurePermission() else { return }
        guard state == .idle else { return }
        do { try capture.start() } catch { return }
        levels = []
        elapsed = 0
        apply(.activateAccessible)
        startTicker()
    }

    /// The touch ended without a release (the system cancelled it: a permission alert, a call, the scene going
    /// inactive). A recording with no finger down is kept as a preview; a press still waiting is dropped.
    public func touchEndedWithoutRelease() {
        switch state {
        case .pending, .recording, .willCancel, .willLock:
            startTask?.cancel()
            apply(.interrupted)
        default: break
        }
    }

    public func stop() { apply(.stop) }
    public func delete() { apply(.delete) }
    public func send() { apply(.sendTapped) }
    /// A call began or the app went to the background.
    public func interrupted() { apply(.interrupted) }

    /// The composer is going away: drop whatever is recording or waiting, sending nothing.
    public func discard() {
        guard state != .idle else { return }
        discardRecording()
        reset()
    }

    public func dismissDenied() { denied = false }

    /// Called about every 50 ms while recording: updates the meter and the time, and ends the note at the limit.
    public func tick() {
        guard isRecording else { return }
        elapsed = capture.elapsed
        levels.append(min(1, max(0, capture.level)))
        if levels.count > Self.barCount { levels.removeFirst(levels.count - Self.barCount) }
        if elapsed >= maxDuration { apply(.limitReached) }
    }

    /// Removes recordings an earlier run left behind (the app was killed while recording or sending).
    public nonisolated static func removeOrphans(in directory: URL = FileManager.default.temporaryDirectory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names where name.hasPrefix("voice-") && (name.hasSuffix(".caf") || name.hasSuffix(".ogg")) {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    // MARK: - Internals

    /// False when recording must not start. A first request that shows the system prompt does not start it.
    private func ensurePermission() async -> Bool {
        switch capture.permission {
        case .granted: return true
        case .denied: denied = true; return false
        case .undetermined:
            if !(await capture.requestPermission()) { denied = true }
            return false
        }
    }

    private func apply(_ event: VoiceGesture.Event) {
        let old = state
        let new = VoiceGesture.step(old, event, rtl: rtl)
        state = new
        switch new {
        case .tooShort:
            discardRecording()
            showHint()
            reset()
        case .cancelled:
            discardRecording()
            reset()
        case .send:
            if let file = old == .preview ? currentPreview() : finishRecording() { deliver(file) } else { reset() }
        case .locked where old != .locked:
            capture.recordingLocked()
        case .preview where old != .preview:
            if let file = finishRecording() {
                previewURL = file.url
                previewDuration = file.duration
                ticker?.cancel()
            } else { reset() }
        default: break
        }
    }

    private func currentPreview() -> (url: URL, duration: Double)? {
        previewURL.map { ($0, previewDuration) }
    }

    private func finishRecording() -> (url: URL, duration: Double)? {
        ticker?.cancel()
        return try? capture.stop()
    }

    private func discardRecording() {
        ticker?.cancel()
        capture.cancel()
        if let url = previewURL { try? FileManager.default.removeItem(at: url) }
        previewURL = nil
    }

    private func deliver(_ file: (url: URL, duration: Double)) {
        if Int(file.duration * 1000) < VoiceGesture.minimumMs {
            try? FileManager.default.removeItem(at: file.url)
            showHint()
        } else {
            onSend(file.url, file.duration)
        }
        previewURL = nil
        reset()
    }

    private func reset() {
        ticker?.cancel()
        startTask?.cancel()
        state = .idle
        levels = []
        elapsed = 0
        previewDuration = 0
        dragOffset = (0, 0)
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(50))
                self?.tick()
            }
        }
    }

    private func showHint() {
        hintVisible = true
        hintTask?.cancel()
        hintTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: self.hintDuration)
            guard !Task.isCancelled else { return }
            self.hintVisible = false
        }
    }
}
