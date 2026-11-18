import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

private let escrowKey = Data(repeating: 7, count: 32)
private let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3])

struct GroupRig {
    let server: FakeServer
    let ana: TestDevice
    /// Two devices each; all have key packages unless `without` names the account.
    let bob: [TestDevice], cy: [TestDevice], dan: [TestDevice]

    static func make(without: Set<String> = []) async throws -> GroupRig {
        let server = FakeServer()
        let ana = await makeDevice(server, account: "acc-ana", username: "ana")
        var all: [String: [TestDevice]] = [:]
        for (account, username) in [("acc-bob", "bob"), ("acc-cy", "cy"), ("acc-dan", "dan")] {
            all[account] = [await makeDevice(server, account: account, username: username),
                            await makeDevice(server, account: account, username: username)]
        }
        for device in [ana] + all.values.flatMap({ $0 }) where !without.contains(device.accountID) {
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        }
        return GroupRig(server: server, ana: ana, bob: all["acc-bob"]!, cy: all["acc-cy"]!, dan: all["acc-dan"]!)
    }

    func manager(_ device: TestDevice) -> ConversationManager {
        ConversationManager(deps: device.deps, escrowKey: { escrowKey })
    }
}

func queued(_ device: TestDevice, _ chatID: String) throws -> [OutboxRecord] {
    try device.deps.repo.dueOutbox(at: testEpochDate.addingTimeInterval(60)).filter { $0.chatID == chatID }
}

@Suite("GroupCreate") struct GroupCreateTests {
    @Test func threeMembersWithTwoDevicesEachAreAddedByOneCommit() async throws {
        let rig = try await GroupRig.make()
        let result = try await rig.manager(rig.ana).createGroup(
            accounts: ["acc-bob", "acc-cy", "acc-dan"], name: "Book club", photo: jpeg)
        #expect(result.notAdded.isEmpty)
        #expect(result.chatID == "g:" + result.conversationID)
        // One commit with six adds: the server is at epoch 1 and holds the creator plus six devices.
        #expect(await rig.server.epoch(of: result.conversationID) == 1)
        #expect(await rig.server.memberDevices(of: result.conversationID).count == 7)
        for device in rig.bob + rig.cy + rig.dan {
            let kinds = try await device.deps.server.inbox(after: nil).items.map(\.kind)
            #expect(kinds == ["welcome", "message"])
        }
        let repo = rig.ana.deps.repo
        let chat = try #require(try repo.chat(id: result.chatID))
        #expect(chat.kind == "group" && chat.title == "Book club" && chat.photo == jpeg && chat.groupVer == 1)
        #expect(chat.isRequest == false && chat.myState == "member")
        #expect(try repo.groupMembers(chatID: result.chatID).map(\.accountID) == ["acc-ana", "acc-bob", "acc-cy", "acc-dan"])
        #expect(try repo.groupMembers(chatID: result.chatID).first?.role == "owner")
        // A group has no safety number and no device lines.
        #expect(try repo.safety(chatID: result.chatID) == nil)
        #expect(try repo.messages(chatID: result.chatID, before: nil, limit: 10).isEmpty)
        let conversation = try #require(try repo.conversation(serverID: result.conversationID))
        #expect(conversation.chatID == result.chatID && conversation.active && conversation.epoch == 1)
    }

    @Test func theGroupEnvelopeIsQueuedOnceAndNothingIsSentBeforeTheModeEnvelope() async throws {
        let rig = try await GroupRig.make()
        let result = try await rig.manager(rig.ana).createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
        let queue = try queued(rig.ana, result.chatID)
        #expect(queue.count == 1)
        guard case .group(let name, let photo, let ver) = ContentEnvelope.decode(queue[0].envelope).body else {
            Issue.record("expected a group envelope"); return
        }
        #expect(name == "Club" && photo == nil && ver == 1)
        // The mode envelope already went out (one stored message on the server: the mode).
        #expect(await rig.server.storedMessages(in: result.conversationID).count == 1)
    }

    @Test func theOwnProfileFollowsWhenThereIsOne() async throws {
        let rig = try await GroupRig.make()
        _ = try rig.ana.deps.repo.saveProfile(accountID: "acc-ana", name: "Ana", photo: nil, ver: 3)
        let result = try await rig.manager(rig.ana).createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
        let bodies = try queued(rig.ana, result.chatID).map { ContentEnvelope.decode($0.envelope).body }
        #expect(bodies.count == 2)
        #expect(bodies.contains(.profile(name: "Ana", photo: nil, ver: 3)))
    }

    @Test func aMemberWithoutKeyPackagesIsToldAndTheOthersAreAdded() async throws {
        let rig = try await GroupRig.make(without: ["acc-dan"])
        let result = try await rig.manager(rig.ana).createGroup(
            accounts: ["acc-bob", "acc-cy", "acc-dan"], name: "Club", photo: nil)
        #expect(result.notAdded == ["acc-dan"])
        #expect(await rig.server.memberDevices(of: result.conversationID).count == 5)
        // The server still lists dan; a later client adds the devices.
        #expect(try rig.ana.deps.repo.groupMembers(chatID: result.chatID).map(\.accountID).contains("acc-dan"))
    }

    @Test func nobodyToAddIsNoKeyPackagesAndNothingIsCreated() async throws {
        let rig = try await GroupRig.make(without: ["acc-bob", "acc-cy"])
        do {
            _ = try await rig.manager(rig.ana).createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
            Issue.record("expected noKeyPackages")
        } catch { #expect(error as? SyncError == .noKeyPackages) }
        #expect(await rig.server.conversationIDs().isEmpty)
    }

    @Test func anUnknownAccountIsNotFound() async throws {
        let rig = try await GroupRig.make()
        do {
            _ = try await rig.manager(rig.ana).createGroup(accounts: ["acc-bob", "acc-cy", "acc-nobody"], name: "Club", photo: nil)
            Issue.record("expected not_found")
        } catch { #expect((error as? ServerError)?.code == "not_found") }
        #expect(try rig.ana.deps.repo.chats().isEmpty)
    }

    @Test func badInputIsRefusedBeforeAnythingHappens() async throws {
        let rig = try await GroupRig.make()
        let manager = rig.manager(rig.ana)
        func fails(_ expected: GroupCreateError, accounts: [String] = ["acc-bob", "acc-cy"], name: String = "Club", photo: Data? = nil) async {
            do { _ = try await manager.createGroup(accounts: accounts, name: name, photo: photo); Issue.record("expected \(expected)") }
            catch { #expect(error as? GroupCreateError == expected) }
        }
        await fails(.invalidName, name: "  ")
        await fails(.invalidName, name: String(repeating: "x", count: 51))
        await fails(.invalidName, name: "a\u{202E}b")
        await fails(.invalidPhoto, photo: Data([0xFF, 0xD8, 1]))                      // under 4 bytes
        await fails(.invalidPhoto, photo: Data([0, 1, 2, 3, 4]))                      // not a JPEG
        await fails(.invalidPhoto, photo: Data([0xFF, 0xD8]) + Data(count: 24_575))   // over 24,576 bytes
        await fails(.tooFewPeople, accounts: ["acc-bob"])
        await fails(.tooFewPeople, accounts: ["acc-bob", "acc-bob", "acc-ana"])
        await fails(.tooManyPeople, accounts: (0..<100).map { "acc-\($0)" })
        #expect(await rig.server.conversationIDs().isEmpty)
        #expect(try rig.ana.deps.repo.chats().isEmpty)
    }

    @Test func myOtherDevicesAreAddedToo() async throws {
        let rig = try await GroupRig.make()
        let ana2 = await makeDevice(rig.server, account: "acc-ana", username: "ana")
        _ = try await ana2.deps.server.uploadKeyPackages(try await ana2.deps.mls.makeKeyPackages(count: 2))
        let result = try await rig.manager(rig.ana).createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
        #expect(await rig.server.memberDevices(of: result.conversationID).count == 6)
        #expect(try await ana2.deps.server.inbox(after: nil).items.map(\.kind).first == "welcome")
    }

    @Test func aGroupOfEqualNamesAndMembersIsAnotherGroup() async throws {
        let rig = try await GroupRig.make()
        let manager = rig.manager(rig.ana)
        let one = try await manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
        let two = try await manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
        #expect(one.chatID != two.chatID)
        #expect(try rig.ana.deps.repo.chats().count == 2)
    }

    @Test func aLostPostLeavesTheGroupPendingAndTheNextStartFinishesItWithoutASecondGroup() async throws {
        let rig = try await GroupRig.make()
        await rig.server.failNextRequests("POST /v1/conversations/", count: 1, status: 503)
        let manager = rig.manager(rig.ana)
        let result = try await manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
        // Durable: the chat and the pending commit are stored; nothing was announced yet.
        #expect(try rig.ana.deps.repo.conversation(serverID: result.conversationID)?.active == false)
        #expect(try queued(rig.ana, result.chatID).isEmpty)
        await manager.resumePendingCreates()
        #expect(try rig.ana.deps.repo.conversation(serverID: result.conversationID)?.active == true)
        #expect(try queued(rig.ana, result.chatID).count == 1)
        #expect(await rig.server.conversationIDs() == [result.conversationID])
        // Resuming again queues nothing twice.
        await manager.resumePendingCreates()
        #expect(try queued(rig.ana, result.chatID).count == 1)
    }

    @Test func aRefusedCommitAbandonsTheGroupAndRemovesItsChat() async throws {
        let rig = try await GroupRig.make()
        await rig.server.failNextRequests("POST /v1/conversations/", count: 1, status: 403)
        do {
            _ = try await rig.manager(rig.ana).createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
            Issue.record("expected the refusal")
        } catch { #expect((error as? ServerError)?.status == 403) }
        #expect(try rig.ana.deps.repo.chats().isEmpty)
    }
}
