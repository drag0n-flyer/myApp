import Testing
import Foundation
import AppCore
@testable import CallCore

private let t0 = Date(timeIntervalSince1970: 1_000)
private func at(_ s: Double) -> Date { t0.addingTimeInterval(s) }

@Suite struct ReconnectPolicyTests {
    @Test func relayPolicyTable() {
        for hiding in IPHiding.allCases {
            for isContact in [true, false] {
                let expected: IceTransportPolicy = hiding.requiresRelay(isContact: isContact) ? .relay : .all
                #expect(RelayDecision.policy(hiding, isContact: isContact) == expected)
            }
        }
    }

    @Test func reconnectTimeline() {
        var p = ReconnectPolicy(isCaller: true)
        #expect(p.ice(.disconnected, at: t0) == [.showReconnecting])
        #expect(p.ice(.disconnected, at: at(1)) == [])
        #expect(p.tick(at: at(2.4)) == [])
        #expect(p.tick(at: at(2.5)) == [.restartIce])
        #expect(p.tick(at: at(5)) == [])
        #expect(p.tick(at: at(7.5)) == [.restartIce])
        #expect(p.ice(.connected, at: at(8)) == [.recovered])
        #expect(p.tick(at: at(20)) == [])
        #expect(p.ice(.connected, at: at(21)) == [])
    }

    @Test func giveUpAfter30s() {
        var p = ReconnectPolicy(isCaller: true)
        _ = p.ice(.disconnected, at: t0)
        #expect(p.tick(at: at(30)) == [.giveUp])
        #expect(p.tick(at: at(31)) == [])
        #expect(p.tick(at: at(40)) == [])
    }

    @Test func failedRestartsImmediately() {
        var p = ReconnectPolicy(isCaller: true)
        #expect(p.ice(.failed, at: t0) == [.showReconnecting, .restartIce])
        #expect(p.tick(at: at(2.5)) == [])
        #expect(p.tick(at: at(5)) == [.restartIce])
    }

    @Test func calleeRequestsRestart() {
        var p = ReconnectPolicy(isCaller: false)
        #expect(p.ice(.disconnected, at: t0) == [.showReconnecting])
        #expect(p.tick(at: at(2.4)) == [])
        #expect(p.tick(at: at(2.5)) == [.requestRestart])
        #expect(p.tick(at: at(7.5)) == [.requestRestart])
        #expect(p.ice(.connected, at: at(8)) == [.recovered])
        var q = ReconnectPolicy(isCaller: false)
        #expect(q.ice(.failed, at: t0) == [.showReconnecting, .requestRestart])
    }

    @Test func pathChangeRestarts() {
        var p = ReconnectPolicy(isCaller: true)
        #expect(p.ice(.connected, at: t0) == [])
        #expect(p.pathChanged(at: at(5)) == [.restartIce])
        var q = ReconnectPolicy(isCaller: false)
        #expect(q.pathChanged(at: at(5)) == [.requestRestart])
    }

    @Test func pathChangeWhileDisconnectedRestartsAndResetsRetry() {
        var p = ReconnectPolicy(isCaller: true)
        _ = p.ice(.disconnected, at: t0)
        #expect(p.pathChanged(at: at(1)) == [.restartIce])
        #expect(p.tick(at: at(5.9)) == [])
        #expect(p.tick(at: at(6)) == [.restartIce])
    }

    @Test func giveUpIsFinal() {
        var p = ReconnectPolicy(isCaller: true)
        _ = p.ice(.disconnected, at: t0)
        _ = p.tick(at: at(30))
        #expect(p.pathChanged(at: at(31)) == [])
        #expect(p.ice(.connected, at: at(32)) == [])
    }

    @Test func meaningfulPathChange() {
        let wifi = PathSnapshot(interface: "wifi", isSatisfied: true)
        let cell = PathSnapshot(interface: "cellular", isSatisfied: true)
        let down = PathSnapshot(interface: "wifi", isSatisfied: false)
        #expect(isMeaningfulChange(from: wifi, to: cell))
        #expect(!isMeaningfulChange(from: wifi, to: wifi))
        #expect(isMeaningfulChange(from: wifi, to: down))
        #expect(isMeaningfulChange(from: down, to: wifi))
        #expect(!isMeaningfulChange(from: down, to: PathSnapshot(interface: "cellular", isSatisfied: false)))
    }
}
