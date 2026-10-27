import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private func ctx() -> CallContext {
    CallContext(server: server, settings: UserSettings(ipHiding: .nonContacts), network: .wifi, isContact: true)
}
private let calleeFP = "sha-256 11:22:33"
private func sdp(_ f: String) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }

private func videoEffects(_ fx: [CallEffect]) -> [(VideoTier?, DataMode)] {
    fx.compactMap { if case .setVideo(let t, let c) = $0 { (t, c) } else { nil } }
}
private func good() -> MediaStats { MediaStats(availableOutgoingBitrate: 900_000, roundTripTime: 0.05) }

@Suite struct VideoCeilingTests {
    /// Outgoing video call, answered with the callee's ceiling (nil = not carried).
    private func caller(peerCeiling: DataMode?) -> CallSessionMachine {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: true, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        _ = m.handle(.remote(CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP, ceiling: peerCeiling),
                             fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp("sha-256 AB:CD")))
        _ = m.handle(.media(.iceState(.connected)))
        return m
    }

    @Test func callWithoutPeerCeilingIsClampedToOwnSetting() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: true, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        let fx = m.handle(.setPreference(.fixed(.balanced)))
        let v = videoEffects(fx)
        #expect(!v.isEmpty)
        #expect(v.allSatisfy { $0.1 == .balanced && ($0.0 ?? .p180) <= .p270 })
        var all: [CallEffect] = []
        for _ in 0..<12 { all += m.handle(.stats(good())) }
        let tiers = videoEffects(all)
        #expect(tiers.allSatisfy { $0.1 == .balanced && ($0.0 ?? .p180) <= .p270 })
    }

    @Test func raisingTheSettingUnclampsToTheHigherTier() {
        var m = caller(peerCeiling: nil)
        _ = m.handle(.setPreference(.fixed(.balanced)))
        let raise = videoEffects(m.handle(.setPreference(.fixed(.high))))
        #expect(raise.contains { $0.1 == .high })   // re-sent at once with the new ceiling
        var all: [CallEffect] = []
        for _ in 0..<12 { all += m.handle(.stats(good())) }
        #expect(videoEffects(all).contains { $0.0 == .p360 && $0.1 == .high })
    }

    @Test func peerCeilingArrivingLowerClampsImmediately() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: true, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        let fx = m.handle(.remote(CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP, ceiling: .balanced),
                                  fromDeviceID: "d1"))
        let v = videoEffects(fx)
        #expect(v.contains { $0.1 == .balanced && ($0.0 ?? .p180) <= .p270 })
        // A later mode change from the peer lowers it again at once.
        var m2 = caller(peerCeiling: nil)
        let fx2 = m2.handle(.remote(CallSignalPayload(kind: .modeChange, callID: "c1", ceiling: .ultraLow), fromDeviceID: "d1"))
        #expect(videoEffects(fx2).allSatisfy { $0.1 == .ultraLow })
    }
}
