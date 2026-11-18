import Testing
@testable import AppCore

@Test func accountActionsRunOneAtATime() {
    var gate = AccountActionGate()
    #expect(gate.running == nil)
    let ok = gate.begin(.savingName)
    #expect(ok)
    #expect(gate.running == .savingName)
    let ok2 = gate.begin(.signingOut)
    #expect(!ok2)
    let ok3 = gate.begin(.deleting)
    #expect(!ok3)
    let ok4 = gate.begin(.savingName)
    #expect(!ok4)
    #expect(gate.running == .savingName)
    gate.finish()
    #expect(gate.running == nil)
    let ok5 = gate.begin(.deleting)
    #expect(ok5)
    #expect(gate.running == .deleting)
}

@Test func theEditorClosesWhenAnotherActionStarts() {
    #expect(AccountActionGate.keepsEditorOpen(running: nil))
    #expect(!AccountActionGate.keepsEditorOpen(running: .signingOut))
    #expect(!AccountActionGate.keepsEditorOpen(running: .deleting))
    #expect(!AccountActionGate.keepsEditorOpen(running: .savingName))
}

@Test func seedStoresTheServersNameWhenNoneIsStored() {
    #expect(OwnNameSeed.nameToStore(stored: "", fetched: "  Asha ", sameSession: true) == "Asha")
}

@Test func seedNeverOverwritesAStoredName() {
    #expect(OwnNameSeed.nameToStore(stored: "Asha", fetched: "Other", sameSession: true) == nil)
}

@Test func seedWritesNothingWhenTheSessionChanged() {
    #expect(OwnNameSeed.nameToStore(stored: "", fetched: "Asha", sameSession: false) == nil)
}

@Test func seedCleansTheServersNameAndSkipsAnEmptyOne() {
    #expect(OwnNameSeed.nameToStore(stored: "", fetched: "Ana\u{202E}xyz", sameSession: true) == "Anaxyz")
    #expect(OwnNameSeed.nameToStore(stored: "", fetched: "", sameSession: true) == nil)
    #expect(OwnNameSeed.nameToStore(stored: "", fetched: "\u{200B}", sameSession: true) == nil)
}

@Test func seedKeepsALongServerNameUpTo64CodePoints() {
    // The server accepts up to 64; a stored name longer than the editor's 40 is cut by `forDisplay` only at 64.
    let long = String(repeating: "a", count: 70)
    #expect(OwnNameSeed.nameToStore(stored: "", fetched: long, sameSession: true) == String(repeating: "a", count: 64))
}
