import Testing
@testable import AppCore

// Ruling 2026-10-28: only the server's proof wipes; any other sign-out keeps the data for the next sign-in to wipe.
@Test func unprovenSignOutWritesNoMarkerAndKeepsTheData() {
    #expect(SignOutPlan.steps(revoked: false, deviceRecorded: true) == [.stop])
}

@Test func revocationWritesTheMarkerBeforeStopping() {
    #expect(SignOutPlan.steps(revoked: true, deviceRecorded: true) == [.markWipePending, .stop, .wipe])
    #expect(SignOutPlan.steps(revoked: true, deviceRecorded: false) == [.markWipePending, .stop, .wipe])
}

// Data with no recorded device could be adopted by the next sign-in to the same account: wipe it instead.
@Test func unprovenSignOutWipesDataWithNoRecordedDevice() {
    #expect(SignOutPlan.steps(revoked: false, deviceRecorded: false) == [.markWipePending, .stop, .wipe])
}
