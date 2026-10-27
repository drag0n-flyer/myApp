import Testing
@testable import AppCore

@Test func titles() {
    #expect(DataMode.allCases.map(\.title) == ["Ultra-low", "Data saver", "Balanced", "High quality"])
    #expect(DataMode.ultraLow.subtitle == "Voice only, smallest data")
    #expect(DataMode.dataSaver.subtitle == "Clear voice, low data")
    #expect(DataMode.balanced.subtitle == nil && DataMode.high.subtitle == nil)
    #expect(ModePreference.auto.title == "Auto")
    #expect(ModePreference.fixed(.dataSaver).title == "Data saver")
}

@Test func ordering() {
    #expect(DataMode.ultraLow < .dataSaver && DataMode.balanced < .high)
}

@Test func estimates() {
    #expect(DataMode.allCases.map(\.estimatedMBPer10Minutes) == [2, 3, 5, 8])
    #expect(DataMode.dataSaver.bytesPerSecond == 5_000)
    #expect(DataFormat.estimate(.ultraLow) == "~2 MB per 10 min")
}
