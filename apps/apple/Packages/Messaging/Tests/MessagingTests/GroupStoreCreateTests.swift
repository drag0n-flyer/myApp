import AppCore
import Foundation
import LocalStore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("GroupStoreCreate") @MainActor struct GroupStoreCreateTests {
    private func make() async throws -> (LiveChatStore, FakeServer) {
        let rig = try await GroupRig.make()
        let deps = rig.ana.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), controls: outbox)
        return (LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil), rig.server)
    }

    @Test func aCreatedGroupIsInTheChatListWithItsNameAndNoContact() async throws {
        let (store, _) = try await make()
        let outcome = await store.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Book club", photo: nil)
        guard case .created(let chatID, let notAdded) = outcome else { Issue.record("expected created, got \(outcome)"); return }
        #expect(notAdded.isEmpty && ChatID.isGroup(chatID))
        let chat = try #require(store.chats(in: .all).first { $0.id == chatID })
        #expect(chat.title == "Book club" && chat.contactID == nil && !chat.isRequest)
    }

    @Test func failuresAreNamedForTheScreen() async throws {
        let (store, _) = try await make()
        #expect(await store.createGroup(accounts: ["acc-bob", "acc-cy", "acc-nobody"], name: "Club", photo: nil) == .failed(.notFound))
        #expect(await store.createGroup(accounts: ["acc-bob"], name: "Club", photo: nil) == .failed(.tooFewPeople))
        #expect(await store.createGroup(accounts: ["acc-bob", "acc-cy"], name: "", photo: nil) == .failed(.invalidName))
        #expect(await store.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: Data([1, 2])) == .failed(.invalidPhoto))
        #expect(store.chats(in: .all).isEmpty)
    }

    @Test func serverErrorsMapToTheirFailure() {
        func f(_ status: Int, _ code: String) -> GroupCreateFailure { GroupCreateFailure(error: ServerError(status: status, code: code)) }
        #expect(f(404, "not_found") == .notFound)
        #expect(f(409, "group_full") == .full)
        #expect(f(429, "rate_limited") == .rateLimited)
        #expect(f(0, "network") == .offline)
        #expect(f(503, "unavailable") == .offline)
        #expect(f(400, "bad_request") == .other)
        #expect(GroupCreateFailure(error: SyncError.noKeyPackages) == .noKeyPackages)
        #expect(GroupCreateFailure(error: GroupCreateError.tooManyPeople) == .tooManyPeople)
    }
}
