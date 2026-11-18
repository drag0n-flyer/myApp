import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging
import MessagingTestKit

@MainActor
private func makeStore(_ account: String = "acc-ana") async -> (LiveChatStore, Repository) {
    let server = FakeServer()
    let device = await makeDevice(server, account: account, username: "ana")
    let deps = device.deps
    let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
    let outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
    let inbox = InboxProcessor(
        deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
        controls: outbox)
    let store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    return (store, deps.repo)
}

@Suite("OwnName") @MainActor struct OwnNameTests {
    @Test func noStoredNameShowsThePlaceholderButStoresNothing() async throws {
        let (store, repo) = await makeStore()
        #expect(store.myStoredName == "")
        #expect(store.me.displayName == "You")
        #expect(try repo.contact(id: "acc-ana") == nil)
    }

    @Test func settingTheNameShowsItStoresItAndKeepsItOutOfContacts() async throws {
        let (store, repo) = await makeStore()
        store.setMyDisplayName("Asha")
        #expect(store.me.displayName == "Asha")
        #expect(store.myStoredName == "Asha")
        let row = try #require(try repo.contact(id: "acc-ana"))
        #expect(row.displayName == "Asha")
        #expect(row.isContact == false)
        #expect(store.contacts.isEmpty)
        store.refresh()
        #expect(store.me.displayName == "Asha")
    }

    @Test func restoringAnEmptyNameBringsBackThePlaceholderNotAStoredYou() async throws {
        let (store, repo) = await makeStore()
        store.setMyDisplayName("Asha")
        store.setMyDisplayName("")
        #expect(store.myStoredName == "")
        #expect(store.me.displayName == "You")
        #expect(try repo.contact(id: "acc-ana")?.displayName == "")
        store.refresh()
        #expect(store.me.displayName == "You")
    }

    @Test func namesFromOtherPeopleAreCleanedForDisplay() async throws {
        let (store, repo) = await makeStore()
        try repo.upsertContact(ContactRecord(
            accountID: "acc-x", username: "xena", displayName: "Ana\u{202E}xyz", isContact: true, addedAt: testEpochDate))
        try repo.upsertContact(ContactRecord(
            accountID: "acc-y", username: "yara", displayName: "\u{200B}\u{3164}", isContact: true, addedAt: testEpochDate))
        store.refresh()
        #expect(store.contact(id: "acc-x")?.displayName == "Anaxyz")
        #expect(store.contact(id: "acc-y")?.displayName == "yara")
    }
}
