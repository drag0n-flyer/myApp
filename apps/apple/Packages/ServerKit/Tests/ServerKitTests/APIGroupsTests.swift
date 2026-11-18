import AppCore
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

@Suite struct APIGroupsTests {
    @Test func createGroupSendsKindAndAccounts() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations", status: 200, json: #"{"conversationID":"g1","mode":"e2ee","epoch":0,"kind":"group"}"#)
        let made = try await makeAuthedClient(t).createGroup(accounts: ["a2", "a3"])
        #expect(made.conversationID == "g1" && made.mode == "e2ee" && made.epoch == 0 && made.kind == "group")
        let body = try jsonBody(await t.requests[0])
        #expect(body.keys.sorted() == ["accounts", "kind"])
        #expect(body["kind"] as? String == "group" && body["accounts"] as? [String] == ["a2", "a3"])
    }

    @Test(arguments: [("not_found", 404), ("group_full", 409), ("rate_limited", 429)])
    func createGroupErrors(code: String, status: Int) async {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations", status: status, json: #"{"error":{"code":"\#(code)","message":"x"}}"#)
        do { _ = try await makeAuthedClient(t).createGroup(accounts: ["a2"]); Issue.record("expected error") } catch {
            #expect(error.code == code && error.status == status)
        }
    }

    @Test func conversationOfAGroupHasKindMembersAndVersion() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/conversations/g1", status: 200, json: """
            {"conversationID":"g1","mode":"server_readable","epoch":4,"kind":"group","membersVersion":3,
             "members":[{"account":"a1","role":"owner"},{"account":"a2","role":"admin"},{"account":"a3","role":"member"}]}
            """)
        let info = try await makeAuthedClient(t).conversation(id: "g1")
        #expect(info.kind == "group" && info.mode == "server_readable" && info.epoch == 4 && info.membersVersion == 3)
        #expect(info.members == [GroupMemberInfo(account: "a1", role: .owner), GroupMemberInfo(account: "a2", role: .admin),
                                 GroupMemberInfo(account: "a3", role: .member)])
    }

    @Test func conversationOfAOneToOneChatDecodesAsBefore() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/conversations/c1", status: 200, json: #"{"conversationID":"c1","mode":"e2ee","epoch":3}"#)
        await t.respond("POST", "/v1/conversations", status: 200, json: #"{"conversationID":"c1","mode":"e2ee","epoch":0,"kind":"direct"}"#)
        let c = makeAuthedClient(t)
        let one = try await c.conversation(id: "c1")
        #expect(one.kind == "direct" && one.members.isEmpty && one.membersVersion == nil && one.epoch == 3)
        let made = try await c.createConversation(with: "a2")
        #expect(made.kind == "direct" && made.members.isEmpty)
    }

    @Test func aRoleTheBuildDoesNotKnowIsAPlainMember() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/conversations/g1", status: 200, json: """
            {"conversationID":"g1","mode":"e2ee","epoch":1,"kind":"group","membersVersion":0,"members":[{"account":"a1","role":"root"}]}
            """)
        #expect(try await makeAuthedClient(t).conversation(id: "g1").members == [GroupMemberInfo(account: "a1", role: .member)])
    }

    @Test func aMemberListOverTheLimitIsNotDecoded() async throws {
        let t = StubTransport()
        let many = (0..<101).map { #"{"account":"a\#($0)","role":"member"}"# }.joined(separator: ",")
        await t.respond("GET", "/v1/conversations/g1", status: 200, json: #"{"conversationID":"g1","mode":"e2ee","epoch":1,"kind":"group","membersVersion":0,"members":[\#(many)]}"#)
        do { _ = try await makeAuthedClient(t).conversation(id: "g1"); Issue.record("expected error") } catch {
            #expect(error.code == "decoding")
        }
        let hundred = (0..<100).map { #"{"account":"a\#($0)","role":"member"}"# }.joined(separator: ",")
        await t.respond("GET", "/v1/conversations/g2", status: 200, json: #"{"conversationID":"g2","mode":"e2ee","epoch":1,"kind":"group","membersVersion":0,"members":[\#(hundred)]}"#)
        let ok = try await makeAuthedClient(t).conversation(id: "g2")
        #expect(ok.members.count == 100)
    }

    @Test func addAccounts() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations/g1/accounts", status: 200, json: #"{"membersVersion":5,"deleted":false}"#)
        let r = try await makeAuthedClient(t).addAccounts(conversationID: "g1", accounts: ["a4", "a5"])
        #expect(r == MembersChange(membersVersion: 5, deleted: false))
        #expect(try jsonBody(await t.requests[0]) as NSDictionary == ["accounts": ["a4", "a5"]] as NSDictionary)
    }

    @Test func removeAccountIsADeleteOnTheAccountPath() async throws {
        let t = StubTransport()
        await t.respond("DELETE", "/v1/conversations/g1/accounts/a4", status: 200, json: #"{"membersVersion":6,"deleted":false}"#)
        let r = try await makeAuthedClient(t).removeAccount(conversationID: "g1", account: "a4")
        #expect(r == MembersChange(membersVersion: 6, deleted: false))
        let req = await t.requests[0]
        #expect(req.httpMethod == "DELETE" && req.httpBody == nil)
    }

    @Test func theLastMemberLeavingDeletesTheConversation() async throws {
        let t = StubTransport()
        await t.respond("DELETE", "/v1/conversations/g1/accounts/a", status: 200, json: #"{"membersVersion":9,"deleted":true}"#)
        #expect(try await makeAuthedClient(t).removeAccount(conversationID: "g1", account: "a").deleted)
    }

    @Test func idsInPathsAreEscaped() async throws {
        let t = StubTransport()
        await t.respond("DELETE", "/v1/conversations/g/1/accounts/a b", status: 200, json: #"{"membersVersion":1,"deleted":false}"#)
        _ = try await makeAuthedClient(t).removeAccount(conversationID: "g/1", account: "a b")
        #expect(await t.requests[0].url?.absoluteString == "https://chat.test/v1/conversations/g%2F1/accounts/a%20b")
    }

    @Test func setRoleSendsTheRole() async throws {
        let t = StubTransport()
        await t.respond("PUT", "/v1/conversations/g1/accounts/a2/role", status: 200, json: #"{"membersVersion":7,"deleted":false}"#)
        let r = try await makeAuthedClient(t).setRole(conversationID: "g1", account: "a2", role: .admin)
        #expect(r.membersVersion == 7)
        let req = await t.requests[0]
        #expect(req.httpMethod == "PUT")
        #expect(try jsonBody(req) as NSDictionary == ["role": "admin"] as NSDictionary)
    }

    @Test(arguments: [("owner_must_transfer", 409), ("group_full", 409), ("forbidden", 403), ("not_found", 404)])
    func membershipErrorsKeepTheirCode(code: String, status: Int) async {
        let t = StubTransport()
        await t.respond("DELETE", "/v1/conversations/g1/accounts/a", status: status, json: #"{"error":{"code":"\#(code)","message":"x"}}"#)
        do { _ = try await makeAuthedClient(t).removeAccount(conversationID: "g1", account: "a"); Issue.record("expected error") } catch {
            #expect(error.code == code && error.status == status)
        }
    }

    @Test func aMembersInboxItemCarriesTheVersionAndNoPayload() throws {
        let json = #"{"cursor":9,"kind":"members","conversationID":"g1","epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"","sentAt":"2026-11-01T12:00:00Z","membersVersion":4}"#
        let item = try ServerJSON.decoder().decode(InboxItem.self, from: Data(json.utf8))
        #expect(item.kind == "members" && item.payload.isEmpty && item.membersVersion == 4)
        #expect(item.membersEvent == GroupMembersEvent(conversationID: "g1", membersVersion: 4))
        let again = try ServerJSON.decoder().decode(InboxItem.self, from: ServerJSON.encoder().encode(item))
        #expect(again == item)
    }

    @Test func onlyAWellFormedMembersItemIsAnEvent() throws {
        func item(_ kind: String, _ version: Int64?, conversation: String = "g1") -> InboxItem {
            InboxItem(cursor: 1, kind: kind, conversationID: conversation, seq: nil, epoch: 0, senderAccountID: "a",
                      senderDeviceID: "d", payload: Data(), sentAt: Date(timeIntervalSince1970: 1), membersVersion: version)
        }
        #expect(item("members", 0).membersEvent == GroupMembersEvent(conversationID: "g1", membersVersion: 0))
        #expect(item("members", nil).membersEvent == nil)
        #expect(item("members", -1).membersEvent == nil)
        #expect(item("members", 3, conversation: "").membersEvent == nil)
        #expect(item("message", 3).membersEvent == nil)
        // The version field on a message item changes nothing.
        let plain = #"{"cursor":7,"kind":"message","conversationID":"c1","seq":1,"epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"AQID","sentAt":"2026-11-01T12:00:00Z"}"#
        let decoded = try ServerJSON.decoder().decode(InboxItem.self, from: Data(plain.utf8))
        #expect(decoded.membersVersion == nil && decoded.membersEvent == nil)
    }
}
