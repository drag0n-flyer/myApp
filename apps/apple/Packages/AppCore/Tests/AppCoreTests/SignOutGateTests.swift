import Testing
@testable import AppCore

// (`#expect` cannot call a mutating method, so each result is bound first.)

@Test func secondSignOutWhileOneRunsIsIgnored() {
    var gate = SignOutGate()
    let first = gate.begin()
    let second = gate.begin()
    #expect(first)
    #expect(!second)
}

@Test func userSignOutSuppressesServerEndedSessionUntilNextSignIn() {
    var gate = SignOutGate()
    _ = gate.begin()
    gate.finished()
    // A 4003 or a late 401 after the sign-out returned: ignored, no second reset, no "removed" alert.
    let afterSignOut = gate.serverEndedSession()
    #expect(!afterSignOut)
    gate.didSignIn()
    let afterNextSignIn = gate.serverEndedSession()
    #expect(afterNextSignIn)
}

@Test func serverEndingTheSessionDuringTheRemovalIsRemembered() {
    var gate = SignOutGate()
    _ = gate.begin()
    let handled = gate.serverEndedSession()
    #expect(!handled)
    #expect(gate.sessionEndedMeanwhile)
}

@Test func failedSignOutLeavesTheDeviceSignedInAndRetryable() {
    var gate = SignOutGate()
    _ = gate.begin()
    gate.failed()
    let removedByOtherDevice = gate.serverEndedSession()   // handled as usual
    #expect(removedByOtherDevice)
    let retry = gate.begin()
    #expect(retry)
}

// A session the server ended meanwhile is gone either way: the confirmed sign-out still wipes.
@Test func signOutWipesWhenTheServerEndedTheSessionMeanwhile() {
    #expect(SignOutDecision.after(revokeError: .server, sessionEndedMeanwhile: true) == .wipe)
    #expect(SignOutDecision.after(revokeError: .network, sessionEndedMeanwhile: true) == .wipe)
}
