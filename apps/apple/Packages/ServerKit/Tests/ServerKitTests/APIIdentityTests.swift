import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import AppCore
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

private func fixture(_ name: String) throws -> String {
    let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")!
    return try String(contentsOf: url, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
}

private func makeClient(_ t: StubTransport, signedIn: Bool = true) -> (ServerClient, SessionStore) {
    let store = SessionStore(storage: MemorySessionStorage())
    if signedIn { try? store.save(Session(host: "chat.test", accountID: "a", deviceID: "d", token: "tok")) }
    return (ServerClient(baseURL: URL(string: "https://chat.test")!, session: store, transport: t), store)
}

private func bodyJSON(_ r: URLRequest) throws -> [String: Any] {
    try JSONSerialization.jsonObject(with: r.httpBody ?? Data()) as? [String: Any] ?? [:]
}

private let sessionJSON = #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#

@Suite struct APIIdentityTests {
    @Test func serverInfoMapsToPolicy() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/server", status: 200, json: try fixture("server-info"))
        let info = try await makeClient(t, signedIn: false).0.serverInfo()
        #expect(info.policy() == ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts,
                                              requiresInvite: true, host: "chat.example.org"))
    }

    @Test func serverInfoModesAndUnknownValues() throws {
        let base = try fixture("server-info")
        let fixed = base.replacingOccurrences(of: #""defaultMode": "auto""#, with: #""defaultMode": "dataSaver""#)
            .replacingOccurrences(of: #""defaultIPHiding": "nonContacts""#, with: #""defaultIPHiding": "always""#)
        let info = try ServerJSON.decoder().decode(ServerInfo.self, from: Data(fixed.utf8))
        #expect(info.policy().defaultPreference == .fixed(.dataSaver))
        #expect(info.policy().defaultIPHiding == .always)
        for bad in [#""maxMode": "turbo""#, #""defaultMode": "turbo""#, #""defaultIPHiding": "sometimes""#] {
            let key = bad.components(separatedBy: ":")[0]
            let broken = base.replacingOccurrences(of: #"\#(key): "[a-zA-Z]+""#, with: bad, options: .regularExpression)
            #expect(throws: (any Error).self) { try ServerJSON.decoder().decode(ServerInfo.self, from: Data(broken.utf8)) }
        }
    }

    @Test func signupStartBody() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        let c = makeClient(t, signedIn: false).0
        let id = try await c.signupStart(inviteCode: "ABCDE-FGHJK", username: "ana", email: "ana@example.org")
        #expect(id == "s1")
        _ = try await c.signupStart(inviteCode: nil, username: "ana", email: "ana@example.org")
        let reqs = await t.requests
        #expect(try bodyJSON(reqs[0]) as? [String: String] == ["inviteCode": "ABCDE-FGHJK", "username": "ana", "email": "ana@example.org"])
        #expect(try bodyJSON(reqs[1]).keys.sorted() == ["email", "username"])
    }

    @Test func signupFinishWithoutCredentialOmitsField() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/signup/finish", status: 200, json: sessionJSON)
        let s = try await makeClient(t, signedIn: false).0.signupFinish(signupID: "s1", credential: nil, deviceName: "Ana's iPhone", platform: "ios")
        #expect(s == NewSession(accountID: "a1", deviceID: "d1", sessionToken: "t1"))
        let body = try bodyJSON(await t.requests[0])
        #expect(body["credential"] == nil)
        #expect(body["platform"] as? String == "ios")
    }

    @Test func creationOptionsParse() throws {
        let o = try ServerJSON.decoder().decode(CreationOptions.self, from: Data(try fixture("creation-options").utf8))
        #expect(o.challenge == Data(0..<16))
        #expect(o.rpID == "chat.test")
        #expect(o.rpName == "Chat")
        #expect(o.userID.count == 16)
        #expect(o.userName == "ana")
        #expect(o.userDisplayName == "ana")
    }

    @Test func registrationCredentialJSON() throws {
        let e = ServerJSON.encoder()
        e.outputFormatting = [.sortedKeys]
        let cred = RegistrationCredential(id: Data([1, 2, 3]), clientDataJSON: Data("foo".utf8), attestationObject: Data([4, 5, 6]))
        let encoded = String(decoding: try e.encode(cred), as: UTF8.self)
        let expected = try fixture("registration-credential")
        #expect(encoded == expected)
    }

    @Test func assertionCredentialJSON() throws {
        let e = ServerJSON.encoder()
        e.outputFormatting = [.sortedKeys]
        let cred = AssertionCredential(id: Data([1]), clientDataJSON: Data([2]), authenticatorData: Data([3]), signature: Data([4]), userHandle: Data([5]))
        let s = String(decoding: try e.encode(cred), as: UTF8.self)
        #expect(s == #"{"id":"AQ","rawId":"AQ","response":{"authenticatorData":"Aw","clientDataJSON":"Ag","signature":"BA","userHandle":"BQ"},"type":"public-key"}"#)
    }

    @Test func linkClaimPendingThenApproved() async throws {
        let t = StubTransport()
        let c = makeClient(t, signedIn: false).0
        await t.respond("POST", "/v1/link/L1/claim", status: 202, json: "{}")
        #expect(try await c.linkClaim(linkID: "L1", claimToken: "ct") == .pending)
        await t.respond("POST", "/v1/link/L1/claim", status: 200,
                        json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1","sealed":"AQID"}"#)
        #expect(try await c.linkClaim(linkID: "L1", claimToken: "ct")
                == .approved(NewSession(accountID: "a1", deviceID: "d1", sessionToken: "t1"), sealed: Data([1, 2, 3])))
    }

    @Test func linkApproveAndInfo() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/link/L1/info", status: 200,
                        json: #"{"deviceName":"New Mac","platform":"macos","createdAt":"2026-11-01T12:00:00.5Z"}"#)
        await t.respond("POST", "/v1/link/L1/approve", status: 204, json: "")
        let c = makeClient(t).0
        let info = try await c.linkInfo(linkID: "L1", secret: "sec")
        #expect(info.deviceName == "New Mac")
        try await c.linkApprove(linkID: "L1", secret: "sec", sealed: Data([251, 255]))
        let reqs = await t.requests
        #expect(reqs[0].value(forHTTPHeaderField: "Authorization") == "Bearer tok")
        #expect(try bodyJSON(reqs[1]) as? [String: String] == ["secret": "sec", "sealed": "-_8"])
    }

    @Test func emailStartAccepts202() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/signin/email/start", status: 202, json: "{}")
        try await makeClient(t, signedIn: false).0.signinEmailStart(email: "ana@example.org")
    }

    @Test func revokeDeviceUsesDelete() async throws {
        let t = StubTransport()
        await t.respond("DELETE", "/v1/devices/d2", status: 204, json: "")
        try await makeClient(t).0.revokeDevice(id: "d2")
        let r = await t.requests[0]
        #expect(r.httpMethod == "DELETE")
        #expect(r.value(forHTTPHeaderField: "Authorization") == "Bearer tok")
    }

    @Test func devicesAndMe() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/devices", status: 200, json: #"{"devices":[{"id":"d1","name":"x","platform":"ios","createdAt":"2026-11-01T12:00:00.123456789Z","lastSeenAt":"2026-11-01T12:00:00Z","current":true}]}"#)
        await t.respond("GET", "/v1/me", status: 200, json: #"{"accountID":"a","username":"ana","email":"e@x.org","displayName":"Ana","findByEmail":false}"#)
        let c = makeClient(t).0
        #expect(try await c.devices().first?.current == true)
        #expect(try await c.me().username == "ana")
    }

    @Test func usernameAndInviteChecks() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/usernames/ana", status: 200, json: #"{"available":true}"#)
        await t.respond("POST", "/v1/invites/check", status: 200, json: #"{"valid":false}"#)
        let c = makeClient(t, signedIn: false).0
        #expect(try await c.usernameAvailable("ana") == true)
        #expect(try await c.checkInvite(code: "X") == false)
    }
}
