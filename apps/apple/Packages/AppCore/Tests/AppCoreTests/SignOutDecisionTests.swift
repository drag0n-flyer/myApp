import Testing
@testable import AppCore

// Review focus: the network drops during sign-out. Nothing is wiped, so the person can try again.
@Test func signOutWipesOnlyWhenTheServerRemovedTheDevice() {
    #expect(SignOutDecision.after(revokeError: nil) == .wipe)
}

@Test func signOutWithNoConnectionKeepsTheData() {
    #expect(SignOutDecision.after(revokeError: .network) == .noConnection)
}

@Test func signOutRefusedByTheServerKeepsTheData() {
    #expect(SignOutDecision.after(revokeError: .server) == .failed)
    #expect(SignOutDecision.after(revokeError: .cancelled) == .failed)
}
