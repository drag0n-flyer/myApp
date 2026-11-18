import AppCore
import Foundation
import LocalStore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("GroupStoreAdmin") @MainActor struct GroupStoreAdminTests {
    private func make() async throws -> (LiveChatStore, GroupRig) {
        let rig = try await GroupRig.make()
        let deps = rig.ana.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), controls: outbox)
        return (LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil), rig)
    }

    @Test func theStoreChangesAGroupAndReadsItsMembers() async throws {
        let (store, _) = try await make()
        guard case .created(let chatID, _) = await store.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil) else {
            Issue.record("expected created"); return
        }
        #expect(store.groupMembers(chatID: chatID).map(\.accountID) == ["acc-ana", "acc-bob", "acc-cy"])
        #expect(await store.addMembers(chatID: chatID, accounts: ["acc-dan"]) == .added(notAdded: []))
        #expect(await store.setRole(chatID: chatID, account: "acc-bob", role: .admin) == .done)
        #expect(store.groupMembers(chatID: chatID).first { $0.accountID == "acc-bob" }?.role == .admin)
        #expect(await store.renameGroup(chatID: chatID, name: "Book club") == .done)
        #expect(store.chat(id: chatID)?.title == "Book club")
        #expect(await store.removeMember(chatID: chatID, account: "acc-cy") == .done)
        #expect(await store.leaveGroup(chatID: chatID) == .failed(.ownerMustTransfer))
        #expect(await store.setRole(chatID: chatID, account: "acc-bob", role: .owner) == .done)
        #expect(await store.leaveGroup(chatID: chatID) == .done)
        #expect(store.chat(id: chatID)?.myGroupState == .left)
        #expect(await store.renameGroup(chatID: chatID, name: "Again") == .failed(.notFound))
    }

    @Test func refusalsAreNamedForTheScreen() async throws {
        #expect(GroupChangeFailure(error: ServerError(status: 403, code: "forbidden")) == .forbidden)
        #expect(GroupChangeFailure(error: ServerError(status: 409, code: "owner_must_transfer")) == .ownerMustTransfer)
        #expect(GroupChangeFailure(error: ServerError(status: 409, code: "group_full")) == .full)
        #expect(GroupChangeFailure(error: ServerError(status: 404, code: "not_found")) == .notFound)
        #expect(GroupChangeFailure(error: ServerError(status: 0, code: "network")) == .offline)
        #expect(GroupChangeFailure(error: GroupAdminError.invalidPhoto) == .invalidPhoto)
        let (store, _) = try await make()
        #expect(await store.addMembers(chatID: "acc-bob", accounts: ["acc-cy"]) == .failed(.notFound))
    }

    @Test func aChatIAmOutOfShowsAsReadOnly() async throws {
        let (store, _) = try await make()
        guard case .created(let chatID, _) = await store.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil) else {
            Issue.record("expected created"); return
        }
        #expect(store.chat(id: chatID)?.myGroupState == .member)
        _ = await store.setRole(chatID: chatID, account: "acc-bob", role: .owner)
        _ = await store.leaveGroup(chatID: chatID)
        #expect(store.chat(id: chatID)?.myGroupState.isReadOnly == true)
    }
}
