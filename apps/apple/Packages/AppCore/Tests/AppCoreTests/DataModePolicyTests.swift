import Testing
@testable import AppCore

let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "home.example.org")

@Test func autoOnWifiIsHigh() {
    #expect(DataModePolicy.ceiling(server: server, settings: .init(), network: .wifi, inCall: nil) == .high)
}

@Test func mobileLimitApplies() {
    #expect(DataModePolicy.ceiling(server: server, settings: .init(), network: .mobile, inCall: nil) == .dataSaver)
}

@Test func inCallFixedLiftsMobileLimit() {
    #expect(DataModePolicy.ceiling(server: server, settings: .init(), network: .mobile, inCall: .fixed(.high)) == .high)
}

@Test func inCallAutoKeepsMobileLimit() {
    #expect(DataModePolicy.ceiling(server: server, settings: .init(), network: .mobile, inCall: .auto) == .dataSaver)
}

// Review Focus 5
@Test func serverMaxBelowChoice() {
    var capped = server
    capped.maximum = .balanced
    var s = CallSettings()
    s.callDataMode = .fixed(.high)
    #expect(DataModePolicy.ceiling(server: capped, settings: s, network: .wifi, inCall: nil) == .balanced)
    #expect(DataModePolicy.ceiling(server: capped, settings: s, network: .wifi, inCall: .fixed(.high)) == .balanced)
    #expect(DataModePolicy.stripLabel(server: capped, settings: s, network: .wifi) == "Balanced")
}

// Review Focus 4
@Test func networkSwitchMidCall() {
    let s = CallSettings()
    #expect(DataModePolicy.ceiling(server: server, settings: s, network: .wifi, inCall: nil) == .high)
    #expect(DataModePolicy.ceiling(server: server, settings: s, network: .mobile, inCall: nil) == .dataSaver)
    #expect(DataModePolicy.ceiling(server: server, settings: s, network: .mobile, inCall: .fixed(.balanced)) == .balanced)
}

@Test func effectiveUsesNetwork() {
    #expect(DataModePolicy.effective(server: server, settings: .init(), network: .wifi, inCall: nil, sustainable: .ultraLow) == .ultraLow)
}

@Test func sheetOnMobileWithServerCap() {
    var capped = server
    capped.maximum = .balanced
    let o = DataModePolicy.sheetOptions(server: capped, settings: .init(), network: .mobile, inCall: nil)
    #expect(o.map(\.title) == ["Auto", "Ultra-low", "Data saver", "Balanced", "High quality"])
    #expect(o[0].isSelected && o[0].estimate == nil)
    #expect(o[3].note == "Above your mobile data limit" && o[3].isEnabled)
    #expect(o[4].note == "Limited by your server" && !o[4].isEnabled)
    #expect(o[2].note == nil && o[2].estimate == "~3 MB per 10 min")
}

@Test func stripLabels() {
    #expect(DataModePolicy.stripLabel(server: server, settings: .init(), network: .wifi) == "Auto")
    #expect(DataModePolicy.stripLabel(server: server, settings: .init(), network: .mobile) == "Data saver")
}
