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
    enum State: Equatable { case idle, running(String), done(String), failed(String) }

    static let sampleRate = 48_000
    static let leadIn = 2.0
    static let tail = 1.0

    @Published private(set) var state: State = .idle

    private var playEngine: AVAudioEngine?
    private var recEngine: AVAudioEngine?

    /// Thread-safe sample sink for the input tap.
    private final class Sink: @unchecked Sendable {
        private let lock = NSLock()
        private var samples: [Int16] = []
        func append(_ s: UnsafeBufferPointer<Int16>) { lock.lock(); samples.append(contentsOf: s); lock.unlock() }
        func take() -> [Int16] { lock.lock(); defer { lock.unlock() }; return samples }
    }

    func run(stimulus: URL, input: AudioDevice, output: AudioDevice, runID: String) async {
        if case .running = state { return }
        state = .running("Recording")
        await go(stimulus, input, output, runID)
    }

    private func go(_ stimulus: URL, _ input: AudioDevice, _ output: AudioDevice, _ runID: String) async {
        do {
            let file = try AVAudioFile(forReading: stimulus)
            let duration = Double(file.length) / file.processingFormat.sampleRate
            let sink = Sink()

            // Recorder: input device, converted to 48 kHz mono 16-bit.
            let rec = AVAudioEngine()
            try Self.setDevice(input.id, on: rec.inputNode)
            let inFormat = rec.inputNode.outputFormat(forBus: 0)
            guard let outFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(Self.sampleRate), channels: 1, interleaved: true),
                  let converter = AVAudioConverter(from: inFormat, to: outFormat) else { throw RigError.format }
            rec.inputNode.installTap(onBus: 0, bufferSize: 4096, format: inFormat) { buffer, _ in
                let cap = AVAudioFrameCount(Double(buffer.frameLength) * outFormat.sampleRate / inFormat.sampleRate) + 64
                guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: cap) else { return }
                var supplied = false
                var err: NSError?
                converter.convert(to: out, error: &err) { _, status in
                    if supplied { status.pointee = .noDataNow; return nil }
                    supplied = true
                    status.pointee = .haveData
                    return buffer
                }
                if err == nil, let p = out.int16ChannelData?[0] {
                    sink.append(UnsafeBufferPointer(start: p, count: Int(out.frameLength)))
                }
            }
            rec.prepare()
            try rec.start()
            recEngine = rec

            // Lead-in silence, then play the stimulus.
            try await Task.sleep(for: .seconds(Self.leadIn))
            state = .running("Playing")
            let play = AVAudioEngine()
            let player = AVAudioPlayerNode()
            play.attach(player)
            try Self.setDevice(output.id, on: play.outputNode)
            play.connect(player, to: play.mainMixerNode, format: file.processingFormat)
            play.prepare()
            try play.start()
            playEngine = play
            player.scheduleFile(file, at: nil)
            player.play()

            try await Task.sleep(for: .seconds(duration + Self.tail))
            player.stop(); play.stop(); playEngine = nil
            rec.inputNode.removeTap(onBus: 0); rec.stop(); recEngine = nil

            let trimmed = RigSchedule.trim(recorded: sink.take(), sampleRate: Self.sampleRate, leadIn: Self.leadIn, duration: duration)
            let dir = try RunExport.makeRunDir(runID: runID)
            let url = dir.appendingPathComponent("received.wav")
            try Self.writeWAV(trimmed, to: url, format: outFormat)
            state = .done("Saved \(trimmed.count) samples to received.wav")
        } catch {
            stop()
            state = .failed(error.localizedDescription)
        }
    }

    func stop() {
        playEngine?.stop(); playEngine = nil
        recEngine?.inputNode.removeTap(onBus: 0); recEngine?.stop(); recEngine = nil
    }

    private enum RigError: LocalizedError {
        case format, device
        var errorDescription: String? { self == .format ? "Could not set up the recording format." : "Could not select that audio device." }
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
