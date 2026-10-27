import Testing
@testable import CallLabKit

@Suite struct SignalingOrderTests {
    // MARK: outbound gate
    @Test func gateQueuesUntilOpenThenSendsInOrder() {
        var g = OutboundGate<String>()
        #expect(g.submit("a").isEmpty)
        #expect(g.submit("b").isEmpty)
        let v1 = g.open()
        #expect(v1 == ["a", "b"])
        let v2 = g.submit("c")
        #expect(v2 == ["c"])
        g.reset()
        #expect(g.submit("d").isEmpty)
        let v3 = g.open()
        #expect(v3 == ["d"])
    }

    // MARK: candidate router
    @Test func callerAcceptsAnyDeviceUntilAnsweredThenLocks() {
        var r = CandidateRouter<String>()
        let v4 = r.route("x1", from: "devA")
        #expect(v4 == .queued)
        let v5 = r.route("y1", from: "devB")
        #expect(v5 == .queued)
        let v6 = r.route("x2", from: "devA")
        #expect(v6 == .queued)
        r.lock(to: "devA")
        let v7 = r.route("y2", from: "devB")
        #expect(v7 == .dropped)
        #expect(r.route("x3", from: "devA") == .queued)       // remote description not set yet
        #expect(r.markRemoteReady() == ["x1", "x2", "x3"])    // devB's candidate is gone
        let v8 = r.route("x4", from: "devA")
        #expect(v8 == .apply)
        let v9 = r.route("y3", from: "devB")
        #expect(v9 == .dropped)
    }

    @Test func calleeLockedFromRing() {
        var r = CandidateRouter<String>()
        r.lock(to: "caller")
        let v10 = r.route("c1", from: "caller")
        #expect(v10 == .queued)
        let v11 = r.route("z", from: "other")
        #expect(v11 == .dropped)
        let v12 = r.markRemoteReady()
        #expect(v12 == ["c1"])
        let v13 = r.route("c2", from: "caller")
        #expect(v13 == .apply)
    }

    @Test func routerAcceptsDeviceBeforeLock() {
        var r = CandidateRouter<String>()
        #expect(r.accepts(from: "anyone"))
        r.lock(to: "d")
        #expect(r.accepts(from: "d") && !r.accepts(from: "e"))
    }

    // MARK: offer glare (perfect negotiation)
    @Test func noCollisionAccepts() {
        var g = OfferGlare(polite: true)
        let v14 = g.incomingOffer(signalingStable: true)
        #expect(v14 == .accept(rollback: false))
    }

    @Test func impoliteIgnoresCollision() {
        var g = OfferGlare(polite: false)
        let v15 = g.incomingOffer(signalingStable: false)
        #expect(v15 == .ignore)
        let t = g.beginLocalOffer()
        #expect(g.incomingOffer(signalingStable: true) == .ignore)   // makingOffer
        #expect(g.isCurrent(t))
    }

    @Test func politeRollsBackAndInvalidatesLocalOffer() {
        var g = OfferGlare(polite: true)
        let t = g.beginLocalOffer()
        // offer in flight (createOffer), signaling still stable: accept, no rollback needed, but the local offer is stale
        let v16 = g.incomingOffer(signalingStable: true)
        #expect(v16 == .accept(rollback: false))
        #expect(!g.isCurrent(t))
        g.endLocalOffer()
        let t2 = g.beginLocalOffer()
        g.endLocalOffer()
        // local offer already set (haveLocalOffer): rollback
        let v17 = g.incomingOffer(signalingStable: false)
        #expect(v17 == .accept(rollback: true))
        #expect(!g.isCurrent(t2))
    }

    @Test func cannotStartOfferWhenBusy() {
        var g = OfferGlare(polite: true)
        #expect(g.canMakeOffer(signalingStable: true))
        #expect(!g.canMakeOffer(signalingStable: false))
        _ = g.beginLocalOffer()
        #expect(!g.canMakeOffer(signalingStable: true))
        g.endLocalOffer()
        #expect(g.canMakeOffer(signalingStable: true))
    }
}
