import Foundation
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("TestFakeServerGroups") struct TestFakeServerGroups {
    private func rig() async throws -> (FakeServer, TestDevice, TestDevice, TestDevice) {
        let server = FakeServer()
        let ana = await makeDevice(server, account: "acc-ana", username: "ana")
        let bob = await makeDevice(server, account: "acc-bob", username: "bob")
        let cy = await makeDevice(server, account: "acc-cy", username: "cy")
        return (server, ana, bob, cy)
    }

    @Test func createListsOwnerAndMembersAndIsReadableByMemberAccounts() async throws {
        let (_, ana, bob, cy) = try await rig()
        let made = try await ana.deps.server.createGroup(accounts: [bob.accountID, cy.accountID])
        #expect(made.kind == "group" && made.epoch == 0 && made.membersVersion == 0)
        #expect(made.members.map(\.account) == ["acc-ana", "acc-bob", "acc-cy"])
        #expect(made.members.map(\.role) == [.owner, .member, .member])
        // Bob's device is not an MLS member yet, but its account is: it can read the list.
        let seen = try await bob.deps.server.conversation(id: made.conversationID)
        #expect(seen.kind == "group" && seen.members.count == 3)
    }

    @Test func unknownAccountIsNotFoundAndAnOutsiderCannotRead() async throws {
        let (server, ana, bob, _) = try await rig()
        do { _ = try await ana.deps.server.createGroup(accounts: [bob.accountID, "acc-nobody"]); Issue.record("expected not_found") }
        catch { #expect(error.code == "not_found") }
        let dan = await makeDevice(server, account: "acc-dan", username: "dan")
        let made = try await ana.deps.server.createGroup(accounts: [bob.accountID])
        do { _ = try await dan.deps.server.conversation(id: made.conversationID); Issue.record("expected not_found") }
        catch { #expect(error.code == "not_found") }
    }

    @Test func moreThan99OthersIsGroupFull() async throws {
        let (_, ana, _, _) = try await rig()
        do { _ = try await ana.deps.server.createGroup(accounts: (0..<100).map { "acc-x\($0)" }); Issue.record("expected group_full") }
        catch { #expect(error.code == "group_full") }
    }

    @Test func anAccountThatBlockedTheCallerIsLeftOutSilently() async throws {
        let (_, ana, bob, cy) = try await rig()
        try await bob.deps.server.block(accountID: ana.accountID)
        let made = try await ana.deps.server.createGroup(accounts: [bob.accountID, cy.accountID])
        #expect(made.members.map(\.account) == ["acc-ana", "acc-cy"])
    }
}
