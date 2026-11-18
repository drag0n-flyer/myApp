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

    @Test func addRemoveAndRoleFollowTheRulesAndBumpTheVersion() async throws {
        let (server, ana, bob, cy) = try await rig()
        let dan = await makeDevice(server, account: "acc-dan", username: "dan")
        let made = try await ana.deps.server.createGroup(accounts: [bob.accountID])
        let id = made.conversationID
        // A plain member may not add; the owner may, and an already-member is skipped without a bump.
        do { _ = try await bob.deps.server.addAccounts(conversationID: id, accounts: [cy.accountID]); Issue.record("expected forbidden") }
        catch { #expect(error.code == "forbidden") }
        let added = try await ana.deps.server.addAccounts(conversationID: id, accounts: [cy.accountID, bob.accountID])
        #expect(added.membersVersion == 1)
        #expect(try await ana.deps.server.addAccounts(conversationID: id, accounts: [bob.accountID]).membersVersion == 1)
        // Roles: only the owner sets them; setting the same role again does not bump.
        _ = try await ana.deps.server.setRole(conversationID: id, account: bob.accountID, role: .admin)
        #expect(try await ana.deps.server.setRole(conversationID: id, account: bob.accountID, role: .admin).membersVersion == 2)
        do { _ = try await bob.deps.server.setRole(conversationID: id, account: cy.accountID, role: .admin); Issue.record("expected forbidden") }
        catch { #expect(error.code == "forbidden") }
        // An admin removes a member but not the owner; an owner cannot leave while others remain.
        do { _ = try await bob.deps.server.removeAccount(conversationID: id, account: ana.accountID); Issue.record("expected forbidden") }
        catch { #expect(error.code == "forbidden") }
        do { _ = try await ana.deps.server.removeAccount(conversationID: id, account: ana.accountID); Issue.record("expected owner_must_transfer") }
        catch { #expect(error.code == "owner_must_transfer") }
        let removed = try await bob.deps.server.removeAccount(conversationID: id, account: cy.accountID)
        #expect(removed.membersVersion == 3 && !removed.deleted)
        // The removed account can no longer read the group.
        do { _ = try await cy.deps.server.conversation(id: id); Issue.record("expected not_found") }
        catch { #expect(error.code == "not_found") }
        // Handing over: the caller becomes an admin; then the old owner may leave.
        _ = try await ana.deps.server.setRole(conversationID: id, account: bob.accountID, role: .owner)
        #expect(await server.groupMembers(of: id).map { "\($0.account):\($0.role)" } == ["acc-ana:admin", "acc-bob:owner"])
        #expect(try await ana.deps.server.removeAccount(conversationID: id, account: ana.accountID).membersVersion == 5)
        _ = dan
    }

    @Test func everyMembershipChangeIsAStoredItemForTheDevicesPresentBefore() async throws {
        let (_, ana, bob, cy) = try await rig()
        let made = try await ana.deps.server.createGroup(accounts: [bob.accountID, cy.accountID])
        // Bob's device joins the conversation through a commit; cy's does not.
        let bobPackage = try await bob.deps.mls.makeKeyPackages(count: 1)
        let group = try await ana.deps.mls.createGroup()
        let bundle = try await ana.deps.mls.add(keyPackages: bobPackage, to: group)
        _ = try await ana.deps.server.postCommit(conversationID: made.conversationID, epoch: 0, commit: bundle.commit, welcome: bundle.welcome,
                                                  addDevices: [bob.deviceID], removeDevices: [])
        _ = try await ana.deps.server.removeAccount(conversationID: made.conversationID, account: cy.accountID)
        let items = try await bob.deps.server.inbox(after: nil, limit: 50).items.filter { $0.kind == "members" }
        #expect(items.count == 1 && items[0].membersEvent == GroupMembersEvent(conversationID: made.conversationID, membersVersion: 1))
        #expect(items[0].senderAccountID == ana.accountID && items[0].payload.isEmpty)
    }

    @Test func aCommitMayNotNameADeviceOfAnAccountThatIsStillAMember() async throws {
        let (_, ana, bob, _) = try await rig()
        let made = try await ana.deps.server.createGroup(accounts: [bob.accountID])
        let group = try await ana.deps.mls.createGroup()
        let package = try await bob.deps.mls.makeKeyPackages(count: 1)
        let bundle = try await ana.deps.mls.add(keyPackages: package, to: group)
        _ = try await ana.deps.server.postCommit(conversationID: made.conversationID, epoch: 0, commit: bundle.commit, welcome: bundle.welcome,
                                                  addDevices: [bob.deviceID], removeDevices: [])
        try await ana.deps.mls.commitAccepted(group)
        let remove = try await ana.deps.mls.remove(members: ["\(bob.accountID)/\(bob.deviceID)"], from: group)
        do {
            _ = try await ana.deps.server.postCommit(conversationID: made.conversationID, epoch: 1, commit: remove.commit, welcome: nil,
                                                      addDevices: [], removeDevices: [bob.deviceID])
            Issue.record("expected forbidden")
        } catch { #expect(error.code == "forbidden") }
    }
}
