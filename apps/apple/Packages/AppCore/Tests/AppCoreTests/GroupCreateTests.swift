import Foundation
import Testing
@testable import AppCore

@Test func aGroupNeedsTwoToNinetyNinePeopleAndAValidName() {
    #expect(GroupCreateRules.needsMorePeople(selected: 0))
    #expect(GroupCreateRules.needsMorePeople(selected: 1))
    #expect(!GroupCreateRules.needsMorePeople(selected: 2))
    #expect(!GroupCreateRules.canCreate(selected: 1, name: "Club"))
    #expect(GroupCreateRules.canCreate(selected: 2, name: "Club"))
    #expect(GroupCreateRules.canCreate(selected: 99, name: "Club"))
    #expect(!GroupCreateRules.canCreate(selected: 100, name: "Club"))
    #expect(!GroupCreateRules.canCreate(selected: 2, name: ""))
    #expect(!GroupCreateRules.canCreate(selected: 2, name: "   "))
    #expect(!GroupCreateRules.canCreate(selected: 2, name: String(repeating: "x", count: 51)))
    #expect(GroupCreateRules.canCreate(selected: 2, name: String(repeating: "x", count: 50)))
}

@MainActor @Test func aSampleGroupAppearsInTheChatListWithItsName() async {
    let store = SampleChatStore(data: .make(now: Date()))
    let people = Array(store.contacts.prefix(2)).map(\.id)
    let outcome = await store.createGroup(accounts: people, name: "Weekend plans", photo: nil)
    guard case .created(let chatID, let notAdded) = outcome else { Issue.record("expected created"); return }
    #expect(notAdded.isEmpty)
    #expect(ChatID.isGroup(chatID))
    let chat = store.chats(in: .all).first { $0.id == chatID }
    #expect(chat?.title == "Weekend plans" && chat?.contactID == nil && chat?.isRequest == false)
}

@MainActor @Test func aSampleGroupWithBadInputIsRefused() async {
    let store = SampleChatStore(data: .make(now: Date()))
    let before = store.chats(in: .all).count
    let one = Array(store.contacts.prefix(1)).map(\.id)
    #expect(await store.createGroup(accounts: one, name: "Club", photo: nil) == .failed(.tooFewPeople))
    let two = Array(store.contacts.prefix(2)).map(\.id)
    #expect(await store.createGroup(accounts: two, name: " ", photo: nil) == .failed(.invalidName))
    #expect(store.chats(in: .all).count == before)
}

@Test func aGroupChatCarriesItsTitle() {
    let chat = Chat(id: "g:1", contactID: nil, encryption: .endToEnd, folderIDs: [], unreadCount: 0, messages: [], isRequest: false, title: "Club")
    #expect(chat.title == "Club")
}
