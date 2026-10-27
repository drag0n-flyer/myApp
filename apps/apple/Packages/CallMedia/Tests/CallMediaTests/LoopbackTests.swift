import XCTest
import AppCore
import CallCore
@testable import CallMedia

/// Two sessions in one process, wired together. Needs the WebRTC XCFramework (Mac CI).
final class LoopbackTests: XCTestCase {
    private var pumps: [Task<Void, Never>] = []
    private var iceStates: [String: [IceState]] = [:]
    private var localCandidates: [String: Int] = [:]
    private let lock = NSLock()

    override func tearDown() { pumps.forEach { $0.cancel() }; pumps = [] }

    private func record(_ name: String, _ e: MediaEvent) {
        lock.withLock {
            switch e {
            case .iceState(let s): iceStates[name, default: []].append(s)
            case .localCandidate: localCandidates[name, default: 0] += 1
            default: break
            }
        }
    }
    private func has(_ name: String, _ s: IceState) -> Bool { lock.withLock { iceStates[name]?.contains(s) ?? false } }

    /// Forwards candidates to `to` (when given) and records every event.
    private func pump(_ name: String, _ from: WebRTCMediaSession, to: WebRTCMediaSession?) {
        pumps.append(Task { [self] in
            for await e in from.events {
                record(name, e)
                if case .localCandidate(let c) = e, let to { try? await to.add(c) }
            }
        })
    }

    private func wait(_ timeout: TimeInterval, _ cond: () -> Bool) async -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if cond() { return true }; try? await Task.sleep(nanoseconds: 100_000_000) }
        return cond()
    }

    private func connected(send: DataMode = .balanced) async throws -> (WebRTCMediaSession, WebRTCMediaSession) {
        let a = WebRTCMediaSession(), b = WebRTCMediaSession()
        try await a.configure(iceServers: [], policy: .all, video: false)
        try await b.configure(iceServers: [], policy: .all, video: false)
        pump("A", a, to: b); pump("B", b, to: a)
        let p = AudioProfile.for(send)
        let offer = try await a.makeOffer(receive: p, iceRestart: false)
        let answer = try await b.acceptOffer(offer, send: p, receive: p)
        try await a.acceptAnswer(answer, send: p)
        return (a, b)
    }

    func testLoopbackConnects() async throws {
        let (a, b) = try await connected()
        let ok = await wait(10) { self.has("A", .connected) && self.has("B", .connected) }
        XCTAssertTrue(ok)
        await a.close(); await b.close()
    }

    func testLocalOfferIsMunged() async throws {
        let a = WebRTCMediaSession()
        try await a.configure(iceServers: [], policy: .all, video: false)
        let offer = try await a.makeOffer(receive: .for(.ultraLow), iceRestart: false)
        XCTAssertTrue(offer.contains("a=ptime:60"))
        XCTAssertTrue(offer.contains("maxaveragebitrate=10000"))
        await a.close()
    }

    func testSendProfileReachesEncoder() async throws {
        let (a, b) = try await connected(send: .ultraLow)
        _ = await wait(10) { self.has("A", .connected) }
        let s0 = await a.stats()
        try await Task.sleep(nanoseconds: 5_000_000_000)
        let s1 = await a.stats()
        let bps = Double(s1.bytesSent - s0.bytesSent) * 8 / 5
        XCTAssertLessThanOrEqual(bps, 14_000)
        await a.close(); await b.close()
    }

    func testRelayOnlyGathersOnlyRelayCandidates() async throws {
        let a = WebRTCMediaSession()
        try await a.configure(iceServers: [], policy: .relay, video: false)
        pump("A", a, to: nil)
        _ = try await a.makeOffer(receive: .for(.balanced), iceRestart: false)
        try await Task.sleep(nanoseconds: 3_000_000_000)
        XCTAssertEqual(lock.withLock { localCandidates["A"] ?? 0 }, 0)
        await a.close()
    }

    func testIceRestartChangesUfrag() async throws {
        let (a, b) = try await connected()
        _ = await wait(10) { self.has("A", .connected) }
        let p = AudioProfile.for(.balanced)
        let first = try await a.makeOffer(receive: p, iceRestart: false)
        try await a.rollback()
        let restart = try await a.makeOffer(receive: p, iceRestart: true)
        XCTAssertNotEqual(Self.ufrag(first), Self.ufrag(restart))
        XCTAssertNotNil(Self.ufrag(restart))
        await a.close(); await b.close()
    }

    func testRollback() async throws {
        let a = WebRTCMediaSession()
        try await a.configure(iceServers: [], policy: .all, video: false)
        _ = try await a.makeOffer(receive: .for(.balanced), iceRestart: false)
        try await a.rollback()
        // After a rollback a new offer can be made.
        let again = try await a.makeOffer(receive: .for(.balanced), iceRestart: false)
        XCTAssertTrue(again.contains("a=ice-ufrag"))
        await a.close()
    }

    private static func ufrag(_ sdp: String) -> String? {
        sdp.components(separatedBy: "\r\n").first { $0.hasPrefix("a=ice-ufrag:") }
    }
}
