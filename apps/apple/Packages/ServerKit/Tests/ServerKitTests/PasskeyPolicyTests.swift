import Testing
@testable import ServerKit

@Suite struct PasskeyPolicyTests {
    @Test func policyExactMatch() {
        #expect(PasskeyPolicy.supported(host: "chat.test", domains: ["Chat.Test"]))
        #expect(!PasskeyPolicy.supported(host: "evil.chat.test", domains: ["Chat.Test"]))
        #expect(!PasskeyPolicy.supported(host: "chat.test", domains: []))
    }
}
