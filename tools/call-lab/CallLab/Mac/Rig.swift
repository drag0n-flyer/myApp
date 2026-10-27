#if os(macOS)
import AVFoundation
import CallLabKit
import CoreAudio
import Foundation

// UNVERIFIED: never compiled (the Mac target needs the WebRTC M156 package to build). Written against the
// AVFoundation / CoreAudio APIs from memory; expect small signature fixes on the first CI build.

struct AudioDevice: Identifiable, Hashable {
    let id: AudioDeviceID
    let name: String
}

enum AudioDevices {
    static func list(input: Bool) -> [AudioDevice] {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == noErr else { return [] }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids) == noErr else { return [] }
        return ids.compactMap { id in
            guard channels(id, input: input) > 0 else { return nil }
            return AudioDevice(id: id, name: name(id))
        }
    }

    private static func channels(_ id: AudioDeviceID, input: Bool) -> Int {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration,
                                              mScope: input ? kAudioDevicePropertyScopeInput : kAudioDevicePropertyScopeOutput,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &addr, 0, nil, &size) == noErr, size > 0 else { return 0 }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, raw) == noErr else { return 0 }
        let list = UnsafeMutableAudioBufferListPointer(raw.assumingMemoryBound(to: AudioBufferList.self))
        return list.reduce(0) { $0 + Int($1.mNumberChannels) }
    }

    private static func name(_ id: AudioDeviceID) -> String {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var cf: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &cf) == noErr, let s = cf?.takeRetainedValue() else { return "Device \(id)" }
        return s as String
    }
}

/// Plays a stimulus WAV on one device and records another device at the same time. Two engines are used
/// because a single AVAudioEngine cannot take a different input and output device on macOS.
@MainActor
final class Rig: ObservableObject {
    enum State: Equatable { case idle, running(String), done(String), failed(String), stopped }

    static let sampleRate = 48_000
    static let leadIn = 2.0
    static let tail = 1.0

    @Published private(set) var state: State = .idle

    private var playEngine: AVAudioEngine?
    private var recEngine: AVAudioEngine?

    /// Thread-safe sample sink for the input tap.
    fileprivate final class Sink: @unchecked Sendable {
        private let lock = NSLock()
        private var samples: [Int16] = []
        func append(_ s: UnsafeBufferPointer<Int16>) { lock.lock(); samples.append(contentsOf: s); lock.unlock() }
        func count() -> Int { lock.lock(); defer { lock.unlock() }; return samples.count }
        func take() -> [Int16] { lock.lock(); defer { lock.unlock() }; return samples }
    }

    private var task: Task<Void, Never>?

    func run(stimulus: URL, input: AudioDevice, output: AudioDevice, runID: String) async {
        if case .running = state { return }
        state = .running("Recording")
        let t = Task { await go(stimulus, input, output, runID) }
        task = t
        await t.value
        task = nil
    }

    /// Builds the input tap block outside the main actor, so the audio thread never runs
    /// main-actor code. The converter is only touched from the tap (one thread at a time).
    private nonisolated static func makeTap(converter: ConverterBox, outFormat: AVAudioFormat, inRate: Double,
                                            sink: Sink) -> AVAudioNodeTapBlock {
        return { buffer, _ in
            let cap = AVAudioFrameCount(Double(buffer.frameLength) * outFormat.sampleRate / inRate) + 64
            guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: cap) else { return }
            var supplied = false
            var err: NSError?
            let status = converter.value.convert(to: out, error: &err) { _, inStatus in
                if supplied { inStatus.pointee = .noDataNow; return nil }
                supplied = true
                inStatus.pointee = .haveData
                return buffer
            }
            if status != .error, err == nil, let p = out.int16ChannelData?[0] {
                sink.append(UnsafeBufferPointer(start: p, count: Int(out.frameLength)))
            }
        }
    }

    private final class ConverterBox: @unchecked Sendable {
        let value: AVAudioConverter
        init(_ v: AVAudioConverter) { value = v }
    }

    private func go(_ stimulus: URL, _ input: AudioDevice, _ output: AudioDevice, _ runID: String) async {
        do {
            let file = try AVAudioFile(forReading: stimulus)
            let duration = Double(file.length) / file.processingFormat.sampleRate
            let sink = Sink()

            // Recorder: input device, converted to 48 kHz mono 16-bit.
            let rec = AVAudioEngine()
            try Self.setDevice(input.id, on: rec.inputNode)
            // HARDWARE CHECK: an input-only device (USB mic) on an engine whose output side is unused may
            // fail at start(). Mirror the device on the output node when it also has outputs.
            if AudioDevices.list(input: false).contains(where: { $0.id == input.id }) {
                try Self.setDevice(input.id, on: rec.outputNode)
            }
            rec.prepare()
            let inFormat = rec.inputNode.outputFormat(forBus: 0)
            guard inFormat.sampleRate > 0, inFormat.channelCount > 0,
                  let outFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(Self.sampleRate), channels: 1, interleaved: true),
                  let converter = AVAudioConverter(from: inFormat, to: outFormat) else { throw RigError.format }
            rec.inputNode.installTap(onBus: 0, bufferSize: 4096, format: inFormat,
                                     block: Self.makeTap(converter: ConverterBox(converter), outFormat: outFormat,
                                                         inRate: inFormat.sampleRate, sink: sink))
            recEngine = rec
            do { try rec.start() } catch { throw RigError.recorderStart(error.localizedDescription) }

            // Player: fully started before the lead-in ends, so playback starts with no setup delay.
            let play = AVAudioEngine()
            let player = AVAudioPlayerNode()
            play.attach(player)
            try Self.setDevice(output.id, on: play.outputNode)
            play.connect(player, to: play.mainMixerNode, format: file.processingFormat)
            player.scheduleFile(file, at: nil, completionHandler: nil)
            play.prepare()
            try play.start()
            playEngine = play

            try await Task.sleep(for: .seconds(Self.leadIn))
            state = .running("Playing")
            // The stimulus starts at the recording position when play() is called.
            let startSample = sink.count()
            player.play()

            try await Task.sleep(for: .seconds(duration + Self.tail))
            player.stop(); play.stop(); playEngine = nil
            rec.inputNode.removeTap(onBus: 0); rec.stop(); recEngine = nil

            let all = sink.take()
            let measuredLeadIn = Double(startSample) / Double(Self.sampleRate)
            let trimmed = RigSchedule.trim(recorded: all, sampleRate: Self.sampleRate, leadIn: measuredLeadIn, duration: duration)
            let needed = Int(duration * Double(Self.sampleRate) * 0.98)
            guard !trimmed.isEmpty, trimmed.count >= needed else { throw RigError.noAudio }
            let dir = try RunExport.makeRunDir(runID: runID)
            let url = dir.appendingPathComponent("received.wav")
            try Self.writeWAV(trimmed, to: url, format: outFormat)
            state = .done("Saved \(trimmed.count) samples to received.wav")
        } catch is CancellationError {
            teardown()
        } catch {
            teardown()
            state = .failed(error.localizedDescription)
        }
    }

    /// Cancels the run, stops both engines and writes no file.
    func stop() {
        task?.cancel()
        teardown()
        if case .running = state { state = .stopped }
    }

    private func teardown() {
        playEngine?.stop(); playEngine = nil
        recEngine?.inputNode.removeTap(onBus: 0); recEngine?.stop(); recEngine = nil
    }

    private enum RigError: LocalizedError {
        case format, device, noAudio, recorderStart(String)
        var errorDescription: String? {
            switch self {
            case .format: return "Could not set up the recording format."
            case .device: return "Could not select that audio device."
            case .noAudio: return "No audio recorded; check microphone permission."
            case .recorderStart(let m): return "Could not start recording from that device (\(m))."
            }
        }
    }

    private static func setDevice(_ id: AudioDeviceID, on node: AVAudioIONode) throws {
        guard let unit = node.audioUnit else { throw RigError.device }
        var dev = id
        let status = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                          &dev, UInt32(MemoryLayout<AudioDeviceID>.size))
        if status != noErr { throw RigError.device }
    }

    private static func writeWAV(_ samples: [Int16], to url: URL, format: AVAudioFormat) throws {
        try? FileManager.default.removeItem(at: url)
        let out = try AVAudioFile(forWriting: url, settings: format.settings, commonFormat: .pcmFormatInt16, interleaved: true)
        guard !samples.isEmpty,
              let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let dst = buf.int16ChannelData?[0] else { return }
        samples.withUnsafeBufferPointer { dst.update(from: $0.baseAddress!, count: samples.count) }
        buf.frameLength = AVAudioFrameCount(samples.count)
        try out.write(from: buf)
    }
}
#endif
