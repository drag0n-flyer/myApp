import Foundation
import Testing
import WebRTC

/// Records what the capture hook is asked to do, then applies `gain`.
final class GainHook: NSObject, RTCAudioCapturePostProcessing, @unchecked Sendable {
    let gain: Float
    private(set) var initializedWith: (rate: Int, channels: Int)?
    private(set) var processedFrames = 0

    init(gain: Float) { self.gain = gain }

    @objc(initializeWithSampleRateHz:channels:)
    func initialize(withSampleRateHz sampleRateHz: Int, channels: Int) {
        initializedWith = (sampleRateHz, channels)
    }

    @objc(processChannels:frames:channelCount:)
    func processChannels(_ channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int) {
        processedFrames += frames
        for c in 0..<channelCount {
            for i in 0..<frames { channels[c][i] *= gain }
        }
    }

    @objc(reset)
    func reset() {}
}

@Suite("Media APIs", .timeLimit(.minutes(1)))
struct MediaAPITests {
    @Test func hardwareH264Listed() {
        let encoders = RTCDefaultVideoEncoderFactory().supportedCodecs().map(\.name)
        let decoders = RTCDefaultVideoDecoderFactory().supportedCodecs().map(\.name)
        #expect(encoders.contains(kRTCVideoCodecH264Name))
        #expect(decoders.contains(kRTCVideoCodecH264Name))
    }

    @Test func statsAvailable() async throws {
        let fixture = try #require(AudioFixture())
        let report = Box<RTCStatisticsReport>()
        fixture.connection.statistics { report.value = $0 }
        let got = try #require(await report.wait(seconds: 5))
        #expect(got.statistics.values.contains { $0.type == "peer-connection" })
    }

    @Test func senderMaxBitrateSettable() throws {
        let fixture = try #require(AudioFixture())
        let sender = fixture.transceiver.sender
        let parameters = sender.parameters
        try #require(!parameters.encodings.isEmpty)
        parameters.encodings[0].maxBitrateBps = NSNumber(value: 16_000)
        sender.parameters = parameters
        #expect(sender.parameters.encodings[0].maxBitrateBps == NSNumber(value: 16_000))
    }

    @Test func capturePostProcessingHook() throws {
        #expect(RTCPeerConnectionFactory.hasCapturePostProcessingHook)

        let zero = GainHook(gain: 0)
        let silenced = RTCPeerConnectionFactory.selfTest(zero).map(\.floatValue)
        #expect(silenced.count == 480)
        #expect(silenced.allSatisfy { $0 == 0 })

        let passThrough = GainHook(gain: 1)
        let samples = RTCPeerConnectionFactory.selfTest(passThrough).map(\.floatValue)
        #expect(samples.count == 480)
        #expect(samples.allSatisfy { abs($0 - 0.5) <= 0.01 })
        #expect(passThrough.initializedWith?.rate == 48_000)
        #expect(passThrough.initializedWith?.channels == 1)

        let factory = RTCPeerConnectionFactory(
            encoderFactory: nil, decoderFactory: nil, capturePostProcessing: passThrough)
        #expect(factory != nil)
    }
}
