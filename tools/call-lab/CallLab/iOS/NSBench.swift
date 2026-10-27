import AVFoundation
import Darwin
import Foundation
import RNNoiseLab

/// Noise-suppression bench. The Mac rig plays a playlist of noisy clips through the cable into the
/// iPhone headset mic; for each clip we record three passes:
///   raw     : input with voice processing off
///   vpio    : input with voice processing on (Apple VPIO)
///   rnnoise : the raw input through `Denoiser`, live
/// Output layout (see `BenchLayout`): `<runDir>/<mode>/<clip-id>.wav` and `<runDir>/bench.json`.
/// NOTE: not compiled on Linux; unverified until L3's app target includes it.
@MainActor
final class NSBench: ObservableObject {
    @Published var status = "Idle"
    @Published var running = false
    @Published var results: [BenchResult] = []

    private(set) var runDir: URL?

    /// `runs/<run_id>/` inside Documents.
    static func makeRunDir(runID: String) throws -> URL {
        let docs = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        let dir = docs.appendingPathComponent("runs", isDirectory: true)
            .appendingPathComponent(runID, isDirectory: true)
        try BenchLayout.prepare(runDir: dir)
        return dir
    }

    /// Runs the three passes for one clip. Each pass records `duration` seconds of live input;
    /// start the Mac-side playback of the same clip before each pass.
    func run(runID: String, clipID: String, duration: TimeInterval) async {
        guard !running else { return }
        running = true
        defer { running = false }
        do {
            let dir = try Self.makeRunDir(runID: runID)
            runDir = dir
            var all = results
            for mode in BenchLayout.modes {
                status = "Recording \(mode) for \(clipID)"
                let url = BenchLayout.wavURL(runDir: dir, mode: mode, clipID: clipID)
                let r = try await Self.recordPass(mode: mode, url: url, duration: duration)
                all.append(r)
                results = all
                if let e = r.error { status = "Refused \(mode): \(e)" }
            }
            try BenchLayout.writeJSON(all, runDir: dir)
            if let e = all.last(where: { $0.error != nil })?.error {
                status = "Done with a refused pass: \(e)"
            } else {
                status = "Done: \(dir.lastPathComponent)"
            }
        } catch {
            status = "Failed: \(error.localizedDescription)"
        }
    }

    // MARK: - One pass

    nonisolated private static func recordPass(mode: String, url: URL, duration: TimeInterval) async throws -> BenchResult {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: mode == "vpio" ? .voiceChat : .measurement,
                                options: [])
        try session.setPreferredSampleRate(48_000)
        try session.setActive(true)

        let engine = AVAudioEngine()
        let input = engine.inputNode
        try input.setVoiceProcessingEnabled(mode == "vpio")

        let format = input.outputFormat(forBus: 0)
        // RNNoise needs 48 kHz. We refuse instead of resampling (a converter would pollute the
        // CPU and quality numbers). The refusal is recorded in bench.json and shown in the UI.
        if let why = BenchRate.refusal(mode: mode, sampleRate: format.sampleRate) {
            try? session.setActive(false)
            return BenchResult(mode: mode, seconds: 0, cpuSeconds: 0,
                               footprintBytesBefore: 0, footprintBytesAfter: 0, error: why)
        }
        // Output file is mono Float32 at the engine rate; rnnoise is fed 48 kHz.
        let fileFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: format.sampleRate,
                                       channels: 1, interleaved: false)!
        let file = try AVAudioFile(forWriting: url, settings: fileFormat.settings)

        let footprintBefore = phys_footprint()
        let tap = TapState(mode: mode, file: file, format: fileFormat)
        let footprintAfterModel = phys_footprint()  // Denoiser is created inside TapState, after `before`

        // @Sendable: must not inherit main-actor isolation; AVAudioEngine calls it on an audio thread.
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { @Sendable buffer, _ in
            tap.handle(buffer)
        }
        engine.prepare()
        try engine.start()
        let start = Date()
        try await Task.sleep(for: .seconds(duration))
        let seconds = Date().timeIntervalSince(start)
        input.removeTap(onBus: 0)
        engine.stop()

        let cpu = tap.cpuSeconds
        return BenchResult(mode: mode, seconds: seconds, cpuSeconds: cpu,
                           footprintBytesBefore: footprintBefore,
                           footprintBytesAfter: max(footprintAfterModel, phys_footprint()))
    }
}

/// Handles tap buffers. For `rnnoise` the samples go through the Denoiser in 480-sample frames,
/// summing thread CPU time around each `process` call. Used from the audio tap thread only,
/// then read after the tap is removed.
private final class TapState: @unchecked Sendable {
    let mode: String
    let file: AVAudioFile
    let format: AVAudioFormat
    let denoiser: Denoiser?
    private var pending: [Float] = []
    private var cpuNanos: UInt64 = 0

    init(mode: String, file: AVAudioFile, format: AVAudioFormat) {
        self.mode = mode
        self.file = file
        self.format = format
        self.denoiser = mode == "rnnoise" ? Denoiser() : nil
    }

    var cpuSeconds: Double { Double(cpuNanos) / 1e9 }

    func handle(_ buffer: AVAudioPCMBuffer) {
        guard let src = buffer.floatChannelData?[0] else { return }
        let n = Int(buffer.frameLength)
        guard let denoiser else {
            write(UnsafeBufferPointer(start: src, count: n))
            return
        }
        // RNNoise expects int16-range floats.
        pending.reserveCapacity(pending.count + n)
        for i in 0..<n { pending.append(src[i] * 32768) }
        var out: [Float] = []
        var offset = 0
        while pending.count - offset >= Denoiser.frameSize {
            var frame = Array(pending[offset..<(offset + Denoiser.frameSize)])
            let t0 = threadCPUNanos()
            _ = denoiser.process(&frame)
            cpuNanos &+= threadCPUNanos() &- t0
            out.append(contentsOf: frame.map { $0 / 32768 })
            offset += Denoiser.frameSize
        }
        pending.removeFirst(offset)
        out.withUnsafeBufferPointer { write($0) }
    }

    private func write(_ samples: UnsafeBufferPointer<Float>) {
        guard !samples.isEmpty,
              let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count))
        else { return }
        buf.frameLength = AVAudioFrameCount(samples.count)
        buf.floatChannelData![0].update(from: samples.baseAddress!, count: samples.count)
        try? file.write(from: buf)
    }
}

private func threadCPUNanos() -> UInt64 {
    var ts = timespec()
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts)
    return UInt64(ts.tv_sec) &* 1_000_000_000 &+ UInt64(ts.tv_nsec)
}

private func phys_footprint() -> UInt64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return kr == KERN_SUCCESS ? UInt64(info.phys_footprint) : 0
}
