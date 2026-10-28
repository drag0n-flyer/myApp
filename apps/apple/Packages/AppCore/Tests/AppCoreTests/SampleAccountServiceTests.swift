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

    @Test func signUpKeepsDisplayName() async throws {
        let s = SampleAccountService()
        try await s.startSignUp(displayName: "Ana", username: "ana", email: "ana@example.org")
        #expect(s.displayName == "Ana")
        try await s.finishSignUp()
        #expect(s.passkeyRegistered)
        #expect(s.isSignedIn)
    }

    @Test func emailCodeSignUpRegistersNoPasskey() async throws {
        let s = SampleAccountService()
        try await s.startSignUp(displayName: "Ana", username: "ana", email: "ana@example.org")
        try await s.finishSignUpWithEmailCode()
        #expect(!s.passkeyRegistered)
        #expect(s.isSignedIn)
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

/// Compile check for the pattern the app views use: a plain `do` would bind `any Error`.
@MainActor
@Suite struct TypedThrowsPatternTests {
    private func capture(_ service: any AccountService) async -> AccountError? {
        var captured: AccountError?
        do throws(AccountError) {
            try await service.verifyEmail(code: "000000")
        } catch {
            captured = error
        }
        return captured
    }

    @Test func doThrowsBindsAccountError() async {
        #expect(await capture(SampleAccountService()) == .codeInvalid)
    }
}
