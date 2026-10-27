import Foundation
import Testing
import WebRTC

@Suite("Opus build", .timeLimit(.minutes(1)))
struct OpusBuildTests {
    @Test func opusVersionIsFloat16() {
        // Named opusVersion because NSObject already has a class method +version.
        let version: String = RTCOpusInfo.opusVersion
        #expect(version == "libopus 1.6")
    }

    @Test func deepPLCActive() { #expect(RTCOpusInfo.deepPLCActive) }
    @Test func osceActive() { #expect(RTCOpusInfo.osceActive) }
    @Test func bweActive() { #expect(RTCOpusInfo.bweActive) }

    @Test func webRTCDecoderUsesComplexity7() {
        #expect(RTCOpusInfo.webRTCDecoderComplexity == 7)
    }

    @Test func plcFieldTrialCompiledIn() {
        #expect(!RTCOpusInfo.opusPLCFieldTrial.isEmpty)
    }

    @Test func packetLengths() {
        #expect(RTCOpusInfo.encoderFrameMs(forPtime: 20) == 20)
        #expect(RTCOpusInfo.encoderFrameMs(forPtime: 40) == 40)
        #expect(RTCOpusInfo.encoderFrameMs(forPtime: 60) == 60)
        // 120 ms works only if the build has 120 ms support (it does at the
        // M156 pin: rtc_opus_support_120ms_ptime exists).
        #expect(RTCOpusInfo.maxPacketMs == 120)
        #expect(RTCOpusInfo.encoderFrameMs(forPtime: 120) == (RTCOpusInfo.maxPacketMs == 120 ? 120 : 0))
    }

    @Test func offerContainsOpus() async throws {
        let fixture = try #require(AudioFixture())
        let offer = try await fixture.connection.offer(for: AudioFixture.offerConstraints)
        #expect(offer.sdp.contains("opus/48000/2"))
    }
}
