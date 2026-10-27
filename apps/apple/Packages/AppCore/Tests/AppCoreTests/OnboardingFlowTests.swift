import Foundation
import Testing
@testable import AppCore

@Test func iPhoneDefaultServerPath() {
    var f = OnboardingFlow(platform: .iPhone)
    f.getStarted()
    #expect(f.step == .joinServer)
    f.choose(.defaultServer, requiresInvite: true)
    #expect(f.step == .inviteCode)
    f.inviteAccepted()
    f.accountSubmitted()
    f.emailVerified()
    f.passkeyCreated()
    f.permissionsHandled()
    #expect(f.step == .done)
}

@Test func inviteLinkSkipsCode() {
    var f = OnboardingFlow(platform: .iPhone)
    f.getStarted()
    f.choose(.invite(.init(host: "chat.example.org", code: "BETA-2026")), requiresInvite: true)
    #expect(f.step == .createAccount)
}

@Test func addressWithoutInvite() {
    var f = OnboardingFlow(platform: .iPhone)
    f.getStarted()
    f.choose(.address("chat.example.org"), requiresInvite: false)
    #expect(f.step == .createAccount)
}

@Test func macIsLinkOnly() {
    var f = OnboardingFlow(platform: .mac)
    #expect(!f.canCreateAccount)
    f.getStarted()
    #expect(f.step == .welcome)
    f.alreadyHaveAccount()
    #expect(f.step == .linkDevice)
}

@Test func lostDevice() {
    var f = OnboardingFlow(platform: .iPhone)
    f.alreadyHaveAccount()
    f.noOldDevice()
    #expect(f.step == .lostDevice)
    f.linked()
    #expect(f.step == .done)
}

@Test func back() {
    var f = OnboardingFlow(platform: .iPhone)
    f.getStarted()
    f.choose(.defaultServer, requiresInvite: true)
    f.back()
    #expect(f.step == .joinServer)
    f.back()
    f.back()
    #expect(f.step == .welcome)
}

@Test func parseInvite() {
    #expect(InviteLink.parse(URL(string: "https://chat.example.org/invite/BETA-2026")!) == .init(host: "chat.example.org", code: "BETA-2026"))
    #expect(InviteLink.parse(URL(string: "https://chat.example.org/other/x")!) == nil)
    #expect(InviteLink.parse(URL(string: "http://chat.example.org/invite/BETA-2026")!) == nil)
    #expect(InviteLink.parse(URL(string: "https://chat.example.org/invite/abc")!) == nil)
}

@MainActor @Test func usernames() async {
    let a = SampleAccountService()
    #expect(await a.checkUsername("yourname") == .available)
    #expect(await a.checkUsername("riya") == .taken)
    #expect(await a.checkUsername("1abc") == .invalid)
    #expect(await a.checkUsername("ab") == .invalid)
    #expect(await a.checkUsername("Upper") == .invalid)
}

@MainActor @Test func codes() async {
    let a = SampleAccountService()
    #expect(await a.verifyEmail(code: "123456"))
    #expect(!(await a.verifyEmail(code: "000000")))
    #expect(await a.redeemInvite(code: "BETA-2026"))
    #expect(!(await a.redeemInvite(code: "nope")))
}
