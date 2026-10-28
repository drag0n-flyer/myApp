import Testing
import Foundation
import AppCore
@testable import CallCore

@Suite struct CallEngineSupportTests {
    @Test func endReasonsMapToSystemCallKinds() {
        #expect(CallKitEndKind(CallEndReason.hungUp) == .remoteEnded)
        #expect(CallKitEndKind(CallEndReason.declined) == .remoteEnded)
        #expect(CallKitEndKind(CallEndReason.missed) == .unanswered)
        #expect(CallKitEndKind(CallEndReason.answeredElsewhere) == .answeredElsewhere)
        #expect(CallKitEndKind(CallEndReason.connectionLost) == .failed)
        #expect(CallKitEndKind(CallEndReason.failed) == .failed)
    }

    @Test func unavailableSealerFailsClosed() async {
        let s = UnavailableCallSignalSealer()
        await #expect(throws: CallSealError.undecryptable) { try await s.seal(CallSignalPayload(kind: .ring, callID: "c"), to: "bob") }
        await #expect(throws: CallSealError.undecryptable) { try await s.open(Data([1]), from: "bob") }
        await #expect(throws: CallSealError.undecryptable) { try await s.openRecoveringSender(Data([1])) }
    }
}
