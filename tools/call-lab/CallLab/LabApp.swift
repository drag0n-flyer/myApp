import AVFoundation
import CallLabKit
import Foundation
import SwiftUI
@preconcurrency import WebRTC

// NOTE: the Call Lab app target is not compiled on Linux. Everything under CallLab/ is UNVERIFIED
// until the WebRTC release exists and the call-lab workflow's `build` job is green.

@main
struct LabApp: App {
    @StateObject private var call: LabCall

    init() {
        let c = LabCall()
        _call = StateObject(wrappedValue: c)
        // Started here, not from a view's .task: a binary launched straight from a shell (CI) may never
        // show its window, and then no view task runs.
        Headless.start(c)
    }

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                ConnectView()
            }
            .environmentObject(call)
        }
        #if os(macOS)
        Window("Rig", id: "rig") { RigView() }
        #endif
    }
}

/// Unattended calls for CI (L10a). Launch arguments (see `LabLaunchOptions`):
/// `--headless --role caller|callee [--preset <name>] [--path direct|relay] [--stimulus <wav>] [--out <dir>]`.
/// Environment: `LAB_HOST`, `LAB_TOKEN`, `LAB_PEER` (caller only), `LAB_SECONDS` (call length, default 300).
/// The caller hangs up after `LAB_SECONDS`; the callee answers the first call and exits when it ends.
/// Exit status: 0 when the call ran and ended, 1 on a call error or timeout, 64 on bad arguments, 66 on a bad stimulus.
@MainActor
enum Headless {
    /// Starts the headless run on the main actor when `--headless` is present.
    static func start(_ call: LabCall) {
        guard CommandLine.arguments.contains("--headless") else { return }
        log("headless: launched")
        Task { @MainActor in await runIfRequested(call) }
    }

    static func runIfRequested(_ call: LabCall) async {
        let opts: LabLaunchOptions
        do {
            guard let o = try LabLaunchOptions.parse(CommandLine.arguments) else { return }
            opts = o
        } catch {
            quit(64, "headless: \(error)")
        }
        let env = ProcessInfo.processInfo.environment
        guard let host = env["LAB_HOST"], let token = env["LAB_TOKEN"], !host.isEmpty, !token.isEmpty else {
            quit(64, "headless: LAB_HOST and LAB_TOKEN are required")
        }
        let seconds = Double(env["LAB_SECONDS"] ?? "") ?? 300
        if let out = opts.out { RunExport.outputDir = URL(fileURLWithPath: out, isDirectory: true) }
        if let s = opts.stimulus {
            do {
                LabCall.capturePostProcessing = try StimulusInjector(url: URL(fileURLWithPath: s))
            } catch {
                quit(66, "headless: stimulus \(s): \(error)")
            }
            if !RTCPeerConnectionFactory.hasCapturePostProcessingHook {
                quit(66, "headless: this WebRTC build has no capture hook, so --stimulus cannot be injected")
            }
        }
        call.knobs = opts.knobs
        call.configure(host: host, token: token)
        log("headless: \(opts.role.rawValue) preset=\(opts.preset.rawValue) path=\(opts.path.rawValue) seconds=\(seconds)")

        switch opts.role {
        case .caller:
            guard let peer = env["LAB_PEER"], !peer.isEmpty else { quit(64, "headless: LAB_PEER is required for the caller") }
            await call.start(peerAccountID: peer)
            _ = await wait(seconds: seconds) { call.finished || call.lastError != nil }
            if !call.finished { await call.hangup() }
        case .callee:
            await call.listen()
            guard await wait(seconds: 120, until: { call.incoming != nil || call.lastError != nil }),
                  call.incoming != nil else {
                quit(1, "headless: no incoming call within 120 s (\(call.lastError ?? "no error"))")
            }
            await call.answer()
            if !(await wait(seconds: seconds + 120, until: { call.finished })) {
                await call.hangup()
                quit(1, "headless: the call did not end within \(Int(seconds + 120)) s")
            }
        }
        if let e = call.lastError { quit(1, "headless: \(e)") }
        quit(0, "headless: done")
    }

    /// Polls `done` every 200 ms; false on timeout.
    private static func wait(seconds: Double, until done: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .milliseconds(Int(seconds * 1000))
        while ContinuousClock.now < deadline {
            if done() { return true }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return done()
    }

    private static func log(_ s: String) { FileHandle.standardError.write(Data((s + "\n").utf8)) }

    private static func quit(_ code: Int32, _ message: String) -> Never {
        log(message)
        exit(code)
    }
}

/// Replaces the captured microphone samples with a WAV stimulus (L8, 50% active speech), then silence after it ends.
/// Runs on WebRTC's audio thread; all state is behind `lock`.
final class StimulusInjector: NSObject, RTCAudioCapturePostProcessing, @unchecked Sendable {
    private let lock = NSLock()
    private let source: AVAudioPCMBuffer
    private var samples: [Float] = []
    private var position = 0

    init(url: URL) throws {
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        guard let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)) else {
            throw LabError.message("Could not allocate the stimulus buffer")
        }
        try file.read(into: buf)
        source = buf
        super.init()
        samples = Self.mono(buf)
    }

    func initialize(withSampleRateHz sampleRateHz: Int, channels: Int) {
        let converted = Self.resample(source, to: Double(sampleRateHz))
        lock.withLock { samples = converted; position = 0 }
    }

    func processChannels(_ channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int) {
        lock.lock(); defer { lock.unlock() }
        // The hook sees full-band float samples in [-1, 1] (patch 0004), the same range AVAudioFile gives.
        for i in 0..<frames {
            let v = position < samples.count ? samples[position] : 0
            position += 1
            for c in 0..<channelCount { channels[c][i] = v }
        }
    }

    func reset() {}

    private static func mono(_ b: AVAudioPCMBuffer) -> [Float] {
        guard let data = b.floatChannelData else { return [] }
        return Array(UnsafeBufferPointer(start: data[0], count: Int(b.frameLength)))
    }

    private static func resample(_ b: AVAudioPCMBuffer, to rate: Double) -> [Float] {
        if b.format.sampleRate == rate { return mono(b) }
        guard let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: 1, interleaved: false),
              let conv = AVAudioConverter(from: b.format, to: outFormat),
              let out = AVAudioPCMBuffer(pcmFormat: outFormat,
                                         frameCapacity: AVAudioFrameCount(Double(b.frameLength) * rate / b.format.sampleRate) + 1024)
        else { return mono(b) }
        // One-shot feed; a box because the input block may be @Sendable.
        final class Feed: @unchecked Sendable {
            var buffer: AVAudioPCMBuffer?
            init(_ b: AVAudioPCMBuffer) { buffer = b }
        }
        let feed = Feed(b)
        var err: NSError?
        _ = conv.convert(to: out, error: &err) { _, status in
            guard let next = feed.buffer else { status.pointee = .endOfStream; return nil }
            feed.buffer = nil
            status.pointee = .haveData
            return next
        }
        return mono(out)
    }
}
