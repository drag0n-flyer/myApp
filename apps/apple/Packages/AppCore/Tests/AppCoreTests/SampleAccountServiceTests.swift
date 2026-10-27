import Foundation
import Testing
@testable import AppCore

@MainActor
@Suite struct SampleAccountServiceTests {
    @Test func wrongCodeThrows() async {
        let s = SampleAccountService()
        await #expect(throws: AccountError.codeInvalid) { try await s.verifyEmail(code: "000000") }
    }

    @Test func rightCodePasses() async throws {
        try await SampleAccountService().verifyEmail(code: SampleAccountService.sampleEmailCode)
    }

    @Test func inviteRedeem() async throws {
        let s = SampleAccountService()
        #expect(try await s.redeemInvite(code: "BETA-2026") == true)
        await #expect(throws: AccountError.inviteInvalid) { _ = try await s.redeemInvite(code: "NOPE") }
    }

    @Test func linkCodeShape() async throws {
        let code = try await SampleAccountService().makeLinkCode()
        #expect(code.qrText.hasPrefix("https://home.example.org/link/"))
    }

    @Test func devicesIncludeThisDevice() async throws {
        let list = try await SampleAccountService().devices()
        #expect(list.filter(\.isThisDevice).count == 1)
    }

    @Test func approveSplitAddsDevice() async throws {
        let s = SampleAccountService()
        let req = try await s.linkRequest(qrText: SampleAccountService.sampleLinkText)
        try await s.approveLink(req)
        #expect(try await s.devices().count == 3)
    }

    @Test func linkRequestRejectsOtherServer() async {
        let s = SampleAccountService()
        await #expect(throws: AccountError.wrongServer) { _ = try await s.linkRequest(qrText: "https://other.example/link/x#y") }
    }

    @Test func recoveredFinishesOnboarding() {
        var f = OnboardingFlow(platform: .iPhone)
        f.alreadyHaveAccount()
        f.noOldDevice()
        #expect(f.step == .lostDevice)
        f.recovered()
        #expect(f.step == .done)
    }
}
