import Testing
@testable import AppCore

@Test func autoDownloadDefaults() {
    let d = AutoDownloadSettings()
    #expect(AutoDownloadPolicy.shouldDownload(.photo, network: .wifi, settings: d))
    #expect(!AutoDownloadPolicy.shouldDownload(.photo, network: .mobile, settings: d))
    #expect(AutoDownloadPolicy.shouldDownload(.voice, network: .mobile, settings: d))
    #expect(!AutoDownloadPolicy.shouldDownload(.voice, network: .offline, settings: d))
    var p = d
    p.onMobile = .photosOnly
    #expect(AutoDownloadPolicy.shouldDownload(.photo, network: .mobile, settings: p))
    #expect(!AutoDownloadPolicy.shouldDownload(.file, network: .mobile, settings: p))
}

@Test func ipHiding() {
    #expect(IPHiding.allCases.map(\.title) == ["Never", "With people not in my contacts", "Always"])
    #expect(IPHiding.never.subtitle == "Fastest; the other person may see your IP")
    #expect(IPHiding.nonContacts.subtitle == "Recommended")
    #expect(IPHiding.always.subtitle == "Calls may be slightly slower")
    #expect(!IPHiding.nonContacts.requiresRelay(isContact: true))
    #expect(IPHiding.nonContacts.requiresRelay(isContact: false))
    #expect(IPHiding.always.requiresRelay(isContact: true) && !IPHiding.never.requiresRelay(isContact: false))
}

@Test func initialFromServer() {
    let server = ServerPolicy(defaultPreference: .fixed(.balanced), maximum: .high, defaultIPHiding: .always, requiresInvite: false, host: "x.org")
    let s = UserSettings.initial(from: server)
    #expect(s.call.callDataMode == .fixed(.balanced) && s.ipHiding == .always)
    #expect(s.call.mobileDataLimit == .dataSaver && s.call.videoOffOnMobile && !s.findByEmail)
}

@Test func syncScopes() {
    let account = Set(SettingKey.allCases.filter { $0.scope == .account })
    #expect(account == [.chatFolders, .contacts, .blockedList, .privacy, .callDataMode, .mobileDataLimit, .readState, .disappearingMessages])
}
