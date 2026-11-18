import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private let calleeFP = "sha-256 AB:CD:EF"
private func sdp(_ f: String) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }
/// Default settings: the mobile-data limit is data saver, so mobile data asks for a lower ceiling than Wi-Fi.
private func ctx(_ network: NetworkType, mobileLimit: DataMode = .dataSaver) -> CallContext {
    var settings = UserSettings(ipHiding: .nonContacts)
    settings.call.mobileDataLimit = mobileLimit
    return CallContext(server: server, settings: settings, network: network, isContact: true)
}

@MainActor
private final class Rig {
    let signaling = FakeCallSignaling()
    let sealer = PlainCallSignalSealer()
    let media = FakeMediaSession()
    let clock = ManualClock()
    var runner: CallRunner!

    init(mobileLimit: DataMode = .dataSaver) {
        media.offerSDP = sdp("sha-256 11:22")
        media.answerSDP = sdp("sha-256 33:44")
        runner = CallRunner(context: ctx(.wifi, mobileLimit: mobileLimit), callID: "c1", contactID: "bob", signaling: signaling,
                            sealer: sealer, clock: clock, makeMedia: { [media] in media }, tickInterval: .seconds(3600),
                            publish: { _ in }, record: { _, _, _, _ in })
    }

    /// A caller whose call is active (ICE connected) on Wi-Fi.
    func activeCaller() async {
        runner.handle(.start(contact: bob, video: false, callID: "c1"))
        await runner.settled()
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP, ceiling: .high)
        let d = try! JSONEncoder().encode(offer)
        sealer.setSender("bob/d1", of: d)
        await runner.ingest(.answered(callID: "c1", deviceID: "d1", payload: d))
        await runner.settled()
        media.emit(.iceState(.connected))
        for _ in 0..<100 where runner.state?.phase != .active { try? await Task.sleep(for: .milliseconds(10)) }
        await runner.settled()
    }

    /// Offers sent to the peer (renegotiate signals that carry an SDP).
    var offersSent: Int {
        signaling.sent.filter { $0.frame == "call.signal" }
            .compactMap { $0.payload.flatMap { try? JSONDecoder().decode(CallSignalPayload.self, from: $0) } }
            .filter { $0.kind == .renegotiate && $0.sdp != nil }.count
    }
}

@MainActor @Suite struct NetworkHandoverTests {
    /// Wi-Fi to mobile data with a data-saver limit: the mode-change offer IS the ICE restart, sent at once, in the new profile.
    @Test func wifiToMobileWithDataSaverSendsOneRestartOffer() async {
        let r = Rig()
        await r.activeCaller()
        #expect(r.runner.state?.phase == .active)
        let before = r.media.offers.count
        let sentBefore = r.offersSent
        r.runner.networkChanged(context: ctx(.mobile))
        await r.runner.settled()
        let offers = Array(r.media.offers.dropFirst(before))
        #expect(offers.count == 1)
        #expect(offers.first?.iceRestart == true)
        #expect(offers.first?.receive.mode == .dataSaver)
        #expect(r.offersSent - sentBefore == 1)   // the one offer went out
    }

    /// Wi-Fi to no connection to mobile data: the engine records "no connection" and does not call the runner,
    /// so nothing is offered while offline; mobile data then gives exactly one restart.
    @Test func offlineThenMobileSendsOneRestartAfter() async {
        let r = Rig()
        await r.activeCaller()
        let before = r.media.offers.count
        await r.runner.settled()
        #expect(r.media.offers.count == before)   // offline: nothing pushed
        r.runner.networkChanged(context: ctx(.mobile))
        await r.runner.settled()
        #expect(Array(r.media.offers.dropFirst(before)).map(\.iceRestart) == [true])
    }

    /// Wi-Fi to mobile with no lower ceiling: no mode change, but the path change still restarts ICE once.
    @Test func pathChangeWithoutModeChangeStillRestartsOnce() async {
        let r = Rig(mobileLimit: .high)
        await r.activeCaller()
        let before = r.media.offers.count
        r.runner.networkChanged(context: ctx(.mobile, mobileLimit: .high))
        await r.runner.settled()
        #expect(Array(r.media.offers.dropFirst(before)).map(\.iceRestart) == [true])
    }

    /// A mode change with no path change (the context moves, no path-change input) is a plain offer, not a restart.
    @Test func modeChangeWithoutPathChangeSendsPlainOffer() async {
        let r = Rig()
        await r.activeCaller()
        let before = r.media.offers.count
        r.runner.update(context: ctx(.mobile))
        await r.runner.settled()
        #expect(Array(r.media.offers.dropFirst(before)).map(\.iceRestart) == [false])
    }
}
