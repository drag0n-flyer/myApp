import Foundation
import Testing
@testable import ServerKit

@Suite struct PasskeyPolicyTests {
    @Test func policyExactMatch() {
        #expect(PasskeyPolicy.supported(host: "chat.test", domains: ["Chat.Test"]))
        #expect(!PasskeyPolicy.supported(host: "evil.chat.test", domains: ["Chat.Test"]))
        #expect(!PasskeyPolicy.supported(host: "chat.test", domains: []))
    }
}

@Suite struct PushHandleTests {
    @Test func hexAndKeys() throws {
        #expect(PushHandle.hex(Data([0, 255, 16])) == "00ff10")
        let json = try JSONEncoder().encode(PushHandle(t: "a", k: "alert", e: "sandbox", b: "x"))
        let obj = try #require(JSONSerialization.jsonObject(with: json) as? [String: Any])
        #expect(obj.keys.sorted() == ["b", "e", "k", "t"])
    }
}
