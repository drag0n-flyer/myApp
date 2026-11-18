import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let alice = Contact(id: "alice", displayName: "Alice", username: "alice")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private let calleeFP = "sha-256 AB:CD:EF"
private let callerFP = "sha-256 11:22:33"
private func sdp(_ f: String) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }
/// Default settings: the mobile-data limit is data saver, so mobile data asks for a lower ceiling than Wi-Fi.
private func ctx(_ network: NetworkType, mobileLimit: DataMode = .dataSaver) -> CallContext {
    var settings = UserSettings(ipHiding: .nonContacts)
    settings.call.mobileDataLimit = mobileLimit
    return CallContext(server: server, settings: settings, network: network, isContact: true)
}

/// One side of a call: a runner with its own fake media and signaling. Frames the other side would receive are
/// delivered by `Pair.pump`, so a test controls what arrives and in which order.
@MainActor
private final class Side {
    let signaling = FakeCallSignaling()
    let sealer = PlainCallSignalSealer()
    let media = FakeMediaSession()
    let clock: ManualClock
    var runner: CallRunner!
    var cursor = 0

    init(contactID: String, offerFP: String, mobileLimit: DataMode, clock: ManualClock) {
        self.clock = clock
        media.offerSDP = sdp(offerFP)
        media.answerSDP = sdp(offerFP)
        runner = CallRunner(context: ctx(.wifi, mobileLimit: mobileLimit), callID: "c1", contactID: contactID, signaling: signaling,
                            sealer: sealer, clock: clock, makeMedia: { [media] in media }, tickInterval: .seconds(3600),
                            publish: { _ in }, record: { _, _, _, _ in })
    }

    var restartOffers: Int { media.offers.filter(\.iceRestart).count }
    func count(_ call: String) -> Int { media.calls.filter { $0 == call }.count }

    /// Offers sent to the peer (renegotiate signals that carry an SDP).
    var offersSent: Int {
        signaling.sent.filter { $0.frame == "call.signal" }
            .compactMap { $0.payload.flatMap { try? JSONDecoder().decode(CallSignalPayload.self, from: $0) } }
            .filter { $0.kind == .renegotiate && $0.sdp != nil }.count
    }
}

@MainActor
private final class Pair {
    let clock = ManualClock()
    let caller: Side
    let callee: Side

    init(mobileLimit: DataMode = .dataSaver) {
        caller = Side(contactID: "bob", offerFP: callerFP, mobileLimit: mobileLimit, clock: clock)
        callee = Side(contactID: "alice", offerFP: calleeFP, mobileLimit: mobileLimit, clock: clock)
    }

    /// Delivers what `from` has sent since the last pump to `to`.
    func pump(from: Side, to: Side) async {
        let sent = from.signaling.sent
        let fresh = sent[from.cursor...]
        from.cursor = sent.count
        let fromCaller = from === caller
        for item in fresh {
            guard let data = item.payload else { continue }
            switch item.frame {
            case "call.invite":
                let ring = try! JSONDecoder().decode(CallSignalPayload.self, from: data)
                to.runner.handle(.incoming(callID: "c1", contact: alice, fromDeviceID: "dA", ring: ring))
            case "call.answer":
                to.sealer.setSender("bob/d1", of: data)
                await to.runner.ingest(.answered(callID: "c1", deviceID: "d1", payload: data))
            case "call.signal":
                let sender = fromCaller ? "alice/dA" : "bob/d1"
                to.sealer.setSender(sender, of: data)
                await to.runner.ingest(.signal(callID: "c1", fromDeviceID: fromCaller ? "dA" : "d1", payload: data))
            default: break
            }
        }
        await to.runner.settled()
    }

    /// Delivers one payload as if `fromCaller` had sent it.
    func deliver(_ p: CallSignalPayload, toCallee: Bool) async {
        let data = try! JSONEncoder().encode(p)
        let to = toCallee ? callee : caller
        to.sealer.setSender(toCallee ? "alice/dA" : "bob/d1", of: data)
        await to.runner.ingest(.signal(callID: "c1", fromDeviceID: toCallee ? "dA" : "d1", payload: data))
        await to.runner.settled()
    }

    /// Both sides active on Wi-Fi.
    func activeCall() async {
        caller.runner.handle(.start(contact: bob, video: false, callID: "c1"))
        await caller.runner.settled()
        await pump(from: caller, to: callee)
        callee.runner.handle(.answer(video: false))
        await callee.runner.settled()
        await pump(from: callee, to: caller)
        await caller.runner.settled()
        await pump(from: caller, to: callee)
        caller.media.emit(.iceState(.connected))
        callee.media.emit(.iceState(.connected))
        for _ in 0..<200 where caller.runner.state?.phase != .active || callee.runner.state?.phase != .active {
            try? await Task.sleep(for: .milliseconds(10))
        }
        await caller.runner.settled(); await callee.runner.settled()
    }
}

@MainActor @Suite struct NetworkHandoverTests {
    // MARK: Pure decision

    @Test func networkActions() {
        #expect(NetworkChange.action(from: .wifi, to: .offline, meaningful: true) == .record)
        #expect(NetworkChange.action(from: .offline, to: .offline, meaningful: true) == .nothing)
        #expect(NetworkChange.action(from: .wifi, to: .mobile, meaningful: true) == .restart)
        #expect(NetworkChange.action(from: .wifi, to: .mobile, meaningful: false) == .contextOnly)
        #expect(NetworkChange.action(from: .wifi, to: .wifi, meaningful: true) == .pathOnly)
        #expect(NetworkChange.action(from: .wifi, to: .wifi, meaningful: false) == .nothing)
        #expect(NetworkChange.action(from: .offline, to: .wifi, meaningful: false) == .restart)   // the network came back
        #expect(NetworkChange.action(from: .offline, to: .mobile, meaningful: false) == .restart)
    }

    // MARK: One side

    /// Wi-Fi to mobile data with a data-saver limit: the mode-change offer IS the ICE restart, sent at once, in the new profile.
    @Test func wifiToMobileWithDataSaverSendsOneRestartOffer() async {
        let p = Pair()
        await p.activeCall()
        #expect(p.caller.runner.state?.phase == .active)
        let before = p.caller.media.offers.count
        let sentBefore = p.caller.offersSent
        p.caller.runner.networkChanged(from: .wifi, to: .mobile, meaningful: true, context: ctx(.mobile))
        await p.caller.runner.settled()
        let offers = Array(p.caller.media.offers.dropFirst(before))
        #expect(offers.count == 1)
        #expect(offers.first?.iceRestart == true)
        #expect(offers.first?.receive.mode == .dataSaver)
        #expect(p.caller.offersSent - sentBefore == 1)
    }

    /// Wi-Fi to no connection: nothing is sent while offline.
    @Test func wifiToOfflineSendsNothing() async {
        let p = Pair()
        await p.activeCall()
        let offers = p.caller.media.offers.count, frames = p.caller.signaling.sent.count
        p.caller.runner.networkChanged(from: .wifi, to: .offline, meaningful: true, context: ctx(.offline))
        await p.caller.runner.settled()
        #expect(p.caller.media.offers.count == offers)
        #expect(p.caller.signaling.sent.count == frames)
    }

    /// Wi-Fi to no connection to mobile data: nothing offline, exactly one restart offer once mobile data arrives.
    @Test func wifiOfflineMobileSendsExactlyOneRestartAfter() async {
        let p = Pair()
        await p.activeCall()
        let before = p.caller.media.offers.count
        p.caller.runner.networkChanged(from: .wifi, to: .offline, meaningful: true, context: ctx(.offline))
        await p.caller.runner.settled()
        #expect(p.caller.media.offers.count == before)
        p.caller.runner.networkChanged(from: .offline, to: .mobile, meaningful: true, context: ctx(.mobile))
        await p.caller.runner.settled()
        #expect(Array(p.caller.media.offers.dropFirst(before)).map(\.iceRestart) == [true])
    }

    /// Wi-Fi to no connection and back to Wi-Fi: one restart (the ceiling did not change, the path did).
    @Test func wifiOfflineWifiSendsExactlyOneRestart() async {
        let p = Pair()
        await p.activeCall()
        let before = p.caller.media.offers.count
        p.caller.runner.networkChanged(from: .wifi, to: .offline, meaningful: true, context: ctx(.offline))
        p.caller.runner.networkChanged(from: .offline, to: .wifi, meaningful: true, context: ctx(.wifi))
        await p.caller.runner.settled()
        #expect(Array(p.caller.media.offers.dropFirst(before)).map(\.iceRestart) == [true])
    }

    /// Wi-Fi to mobile with no lower ceiling: no mode change, but the path change still restarts ICE once.
    @Test func pathChangeWithoutModeChangeStillRestartsOnce() async {
        let p = Pair(mobileLimit: .high)
        await p.activeCall()
        let before = p.caller.media.offers.count
        p.caller.runner.networkChanged(from: .wifi, to: .mobile, meaningful: true, context: ctx(.mobile, mobileLimit: .high))
        await p.caller.runner.settled()
        #expect(Array(p.caller.media.offers.dropFirst(before)).map(\.iceRestart) == [true])
    }

    /// A mode change with no path change (the context moves, no path-change input) is a plain offer, not a restart.
    @Test func modeChangeWithoutPathChangeSendsPlainOffer() async {
        let p = Pair()
        await p.activeCall()
        let before = p.caller.media.offers.count
        p.caller.runner.update(context: ctx(.mobile))
        await p.caller.runner.settled()
        #expect(Array(p.caller.media.offers.dropFirst(before)).map(\.iceRestart) == [false])
    }

    /// While the call is ringing or connecting nothing restarts; the call only learns its new context.
    @Test func noRestartWhileRinging() async {
        let p = Pair()
        p.caller.runner.handle(.start(contact: bob, video: false, callID: "c1"))
        await p.caller.runner.settled()
        #expect(p.caller.runner.state?.phase == .ringing)
        let before = p.caller.media.offers.count
        p.caller.runner.networkChanged(from: .wifi, to: .mobile, meaningful: true, context: ctx(.mobile))
        p.caller.runner.networkChanged(from: .mobile, to: .mobile, meaningful: true, context: ctx(.mobile))
        await p.caller.runner.settled()
        #expect(p.caller.media.offers.count == before)
        #expect(p.caller.runner.state?.network == .mobile)
    }

    // MARK: Both sides

    /// The callee goes Wi-Fi to mobile with a lower ceiling: the caller accepts the callee's restart offer and answers,
    /// and exactly one ICE restart is sent (no `modeChange` ahead of it that would make the caller offer first).
    @Test func calleeHandoverDeliversOneRestartTheCallerAccepts() async {
        let p = Pair()
        await p.activeCall()
        let callerOffers = p.caller.media.offers.count, accepts = p.caller.count("acceptOffer")
        let calleeOffers = p.callee.media.offers.count, answers = p.callee.count("acceptAnswer")
        p.callee.runner.networkChanged(from: .wifi, to: .mobile, meaningful: true, context: ctx(.mobile))
        await p.callee.runner.settled()
        await p.pump(from: p.callee, to: p.caller)
        await p.pump(from: p.caller, to: p.callee)
        #expect(p.caller.count("acceptOffer") - accepts == 1)
        #expect(p.caller.media.offers.count == callerOffers)          // the caller sent no offer of its own
        #expect(Array(p.callee.media.offers.dropFirst(calleeOffers)).map(\.iceRestart) == [true])
        #expect(p.callee.count("acceptAnswer") - answers == 1)        // the caller's answer came back
        #expect(p.caller.restartOffers + p.callee.restartOffers == 1)
    }

    /// A `modeChange` that arrives after the restart offer causes no further offer.
    @Test func lateModeChangeCausesNoFurtherOffer() async {
        let p = Pair()
        await p.activeCall()
        p.callee.runner.networkChanged(from: .wifi, to: .mobile, meaningful: true, context: ctx(.mobile))
        await p.callee.runner.settled()
        await p.pump(from: p.callee, to: p.caller)
        await p.pump(from: p.caller, to: p.callee)
        let offers = p.caller.media.offers.count
        await p.deliver(CallSignalPayload(kind: .modeChange, callID: "c1", ceiling: .dataSaver), toCallee: false)
        #expect(p.caller.media.offers.count == offers)
        #expect(p.caller.restartOffers + p.callee.restartOffers == 1)
    }

    /// Both sides change at once: the caller's restart wins, the callee rolls its own back and answers.
    @Test func glareCallerRestartWinsCalleeRollsBack() async {
        let p = Pair()
        await p.activeCall()
        let rollbacks = p.callee.count("rollback"), calleeAccepts = p.callee.count("acceptOffer")
        let callerAccepts = p.caller.count("acceptOffer"), callerRollbacks = p.caller.count("rollback")
        let callerAnswers = p.caller.count("acceptAnswer")
        p.caller.runner.networkChanged(from: .wifi, to: .mobile, meaningful: true, context: ctx(.mobile))
        p.callee.runner.networkChanged(from: .wifi, to: .mobile, meaningful: true, context: ctx(.mobile))
        await p.caller.runner.settled(); await p.callee.runner.settled()
        await p.pump(from: p.caller, to: p.callee)
        await p.pump(from: p.callee, to: p.caller)
        #expect(p.callee.count("rollback") - rollbacks == 1)
        #expect(p.callee.count("acceptOffer") - calleeAccepts == 1)
        #expect(p.caller.count("acceptOffer") == callerAccepts)       // the callee's offer was ignored
        #expect(p.caller.count("rollback") == callerRollbacks)
        #expect(p.caller.count("acceptAnswer") - callerAnswers == 1)  // the callee's answer to the caller's offer
    }
}
