import Foundation
import Testing
@testable import AppCore

@MainActor
@Test func aSampleGroupHasTwoSendersAndMessageInfoListsBothMembers() async throws {
    let store = SampleChatStore()
    guard case .created(let chatID, _) = await store.createGroup(accounts: ["riya", "arjun"], name: "Weekend plans", photo: nil) else {
        Issue.record("not created"); return
    }
    let messages = store.messages(in: chatID, before: nil, limit: 20)
    #expect(Set(messages.map(\.senderID)) == ["riya", "arjun", "me"])
    #expect(messages.filter { $0.senderID != "me" }.count == 2)
    #expect(store.memberName("riya") == "Riya" && store.memberName("arjun") == "Arjun" && store.memberName("nobody") == "Someone")
    let mine = try #require(messages.last { $0.senderID == "me" })
    let lists = store.receiptLists(messageID: mine.id, in: chatID)
    #expect(lists.readBy.map(\.name) == ["Riya"] && lists.deliveredTo.map(\.name) == ["Arjun"])
    // Not mine, or not a group: nothing.
    let theirs = try #require(messages.first { $0.senderID == "riya" })
    #expect(store.receiptLists(messageID: theirs.id, in: chatID) == MessageReceiptLists())
    #expect(store.receiptLists(messageID: mine.id, in: "chat-riya") == MessageReceiptLists())
}
