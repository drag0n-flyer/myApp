import AVFoundation
import Foundation
import Testing
import WebRTC

enum Loopback {
    /// False in the simulator and on CI runners. Plan 5 runs this test on the
    /// owner's devices.
    static var hasMicrophone: Bool { AVCaptureDevice.default(for: .audio) != nil }

    /// Adds `a=ptime:60` and `a=maxptime:60` to the audio section of an SDP.
    static func munge(_ sdp: String) -> String {
        var lines = sdp.components(separatedBy: "\r\n")
        while lines.last == "" { lines.removeLast() }
        guard let audio = lines.firstIndex(where: { $0.hasPrefix("m=audio") }) else { return sdp }
        let end = lines[(audio + 1)...].firstIndex(where: { $0.hasPrefix("m=") }) ?? lines.endIndex
        lines.insert(contentsOf: ["a=ptime:60", "a=maxptime:60"], at: end)
        return lines.joined(separator: "\r\n") + "\r\n"
    }

    static func outboundPacketsSent(_ connection: RTCPeerConnection) async -> Double? {
        let report = Box<RTCStatisticsReport>()
        connection.statistics { report.value = $0 }
        guard let got = await report.wait(seconds: 5) else { return nil }
        let sent = got.statistics.values
            .filter { $0.type == "outbound-rtp" }
            .compactMap { ($0.values["packetsSent"] as? NSNumber)?.doubleValue }
        return sent.isEmpty ? nil : sent.reduce(0, +)
    }
}

@Suite("Loopback", .timeLimit(.minutes(2)))
struct LoopbackTests {
    @Test(.enabled(if: Loopback.hasMicrophone))
    func loopbackPtime60() async throws {
        let a = try #require(AudioFixture())
        let b = try #require(AudioFixture())
        for fixture in [a, b] {
            let source = fixture.factory.audioSource(with: nil)
            fixture.transceiver.sender.track = fixture.factory.audioTrack(with: source, trackId: "audio0")
        }
        // Candidates go straight to the other side.
        a.delegate.onCandidate = { b.connection.add($0) { _ in } }
        b.delegate.onCandidate = { a.connection.add($0) { _ in } }

        let offer = try await a.connection.offer(for: AudioFixture.offerConstraints)
        let mungedOffer = RTCSessionDescription(type: .offer, sdp: Loopback.munge(offer.sdp))
        try await a.connection.setLocalDescription(mungedOffer)
        try await b.connection.setRemoteDescription(mungedOffer)

        let answer = try await b.connection.answer(for: AudioFixture.offerConstraints)
        let mungedAnswer = RTCSessionDescription(type: .answer, sdp: Loopback.munge(answer.sdp))
        try await b.connection.setLocalDescription(mungedAnswer)
        try await a.connection.setRemoteDescription(mungedAnswer)

        let deadline = Date().addingTimeInterval(10)
        func connected(_ f: AudioFixture) -> Bool { f.delegate.iceState == .connected || f.delegate.iceState == .completed }
        while !(connected(a) && connected(b)), Date() < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }
        try #require(connected(a) && connected(b), "peer connections did not connect within 10 s")

        try await Task.sleep(for: .seconds(4))
        let first = try #require(await Loopback.outboundPacketsSent(a.connection))
        try await Task.sleep(for: .seconds(3))
        let second = try #require(await Loopback.outboundPacketsSent(a.connection))

        // One 60 ms packet is about 16.7 packets per second.
        let rate = (second - first) / 3
        #expect((14.0...19.0).contains(rate), "sent \(rate) packets per second")

        a.connection.close()
        b.connection.close()
    }
}
