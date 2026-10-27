import Testing
@testable import Messaging

@Suite("TestPendingActionsGate") struct TestPendingActionsGate {
    @Test func gatesAreIndependentPerStore() {
        let a = PendingActions.Gate(), b = PendingActions.Gate()
        #expect(a.enter())
        #expect(b.enter())   // another store's run in flight must not skip this one
    }

    @Test func requestDuringARunMakesTheRunGoAgain() {
        let gate = PendingActions.Gate()
        #expect(gate.enter())
        #expect(!gate.enter())          // arrives while busy: not run now...
        #expect(gate.leave())           // ...but the running one must loop once more
        #expect(!gate.leave())          // nothing new: done
        #expect(gate.enter())           // free again
    }
}
