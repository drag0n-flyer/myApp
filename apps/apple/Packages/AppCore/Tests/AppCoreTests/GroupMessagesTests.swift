import Foundation
import Testing
@testable import AppCore

private let t0 = Date(timeIntervalSince1970: 1_000)
private func r(_ id: String, _ kind: String, _ s: Double = 0) -> GroupReceipt {
    GroupReceipt(accountID: id, kind: kind, at: Date(timeIntervalSince1970: s))
}

@Test func theGroupTickNeedsEveryOtherMember() {
    let members = ["me", "a", "b", "c"]
    #expect(GroupTicks.state(receipts: [], members: members, me: "me") == .sent)
    #expect(GroupTicks.state(receipts: [r("a", "delivered"), r("b", "delivered")], members: members, me: "me") == .sent)
    #expect(GroupTicks.state(receipts: [r("a", "delivered"), r("b", "delivered"), r("c", "delivered")], members: members, me: "me") == .delivered)
    // A read counts as delivered.
    #expect(GroupTicks.state(receipts: [r("a", "read"), r("b", "delivered"), r("c", "read")], members: members, me: "me") == .delivered)
    #expect(GroupTicks.state(receipts: [r("a", "read"), r("b", "read"), r("c", "read")], members: members, me: "me") == .read)
    #expect(GroupTicks.state(receipts: [r("a", "read"), r("b", "read")], members: members, me: "me") == .sent)
}

@Test func aMemberWhoLeftDoesNotHoldTheTickBackAndAStrangerDoesNotMoveIt() {
    // c left: the list is now me, a, b.
    #expect(GroupTicks.state(receipts: [r("a", "read"), r("b", "read")], members: ["me", "a", "b"], me: "me") == .read)
    // A receipt from an account that is not a member counts for nothing.
    #expect(GroupTicks.state(receipts: [r("x", "read"), r("a", "read")], members: ["me", "a", "b"], me: "me") == .sent)
    // My own receipt (another device) counts for nothing.
    #expect(GroupTicks.state(receipts: [r("me", "read"), r("a", "read")], members: ["me", "a", "b"], me: "me") == .sent)
    // Alone in the group: nothing to wait for, nothing to tick.
    #expect(GroupTicks.state(receipts: [], members: ["me"], me: "me") == .sent)
    // Unknown kinds count for nothing.
    #expect(GroupTicks.state(receipts: [r("a", "seen")], members: ["me", "a"], me: "me") == .sent)
}

@Test func messageInfoListsReadByAndDeliveredToByTimeThenAccount() {
    let lists = GroupTicks.lists(
        receipts: [r("b", "delivered", 5), r("a", "delivered", 3), r("a", "read", 9), r("c", "delivered", 5), r("c", "read", 7), r("x", "read", 1), r("me", "read", 1), r("d", "delivered", 5)],
        members: ["me", "a", "b", "c", "d"], me: "me")
    #expect(lists.readBy.map(\.accountID) == ["c", "a"])
    #expect(lists.readBy.map(\.at) == [Date(timeIntervalSince1970: 7), Date(timeIntervalSince1970: 9)])
    #expect(lists.deliveredTo.map(\.accountID) == ["b", "d"])
}

private func msg(_ id: String, _ sender: String, _ content: MessageContent = .text("hi"), at: Date = t0) -> Message {
    Message(id: id, senderID: sender, sentAt: at, content: content, status: .delivered, replyToID: nil, reactions: [])
}

@Test func theNameGoesInFrontOfTheLastMessageOfAGroupOnly() {
    func chat(_ id: String, _ last: Message) -> Chat {
        Chat(id: id, contactID: id.hasPrefix("g:") ? nil : id, encryption: .endToEnd, folderIDs: [], unreadCount: 0, messages: [last], isRequest: false)
    }
    #expect(GroupLines.previewSender(in: chat("g:1", msg("1", "me")), me: "me") == .me)
    #expect(GroupLines.previewSender(in: chat("g:1", msg("1", "ana")), me: "me") == .other("ana"))
    #expect(GroupLines.previewSender(in: chat("g:1", msg("1", "system", .system(.addedYou(by: nil)))), me: "me") == nil)
    #expect(GroupLines.previewSender(in: chat("ana", msg("1", "ana")), me: "me") == nil)
    #expect(GroupLines.previewSender(in: Chat(id: "g:1", contactID: nil, encryption: .endToEnd, folderIDs: [], unreadCount: 0, messages: [], isRequest: false), me: "me") == nil)
}

@Test func theSenderShowsOnceAtTheStartOfEachRun() {
    let cal = Calendar(identifier: .gregorian)
    let list = [msg("1", "a"), msg("2", "a"), msg("3", "b"), msg("4", "me"), msg("5", "b"), msg("6", "b"),
                msg("7", "system", .system(.messagesPaused)), msg("8", "b"),
                msg("9", "b", at: t0.addingTimeInterval(3 * 86_400))]
    let shows = list.indices.map { GroupLines.showsSender(at: $0, in: list, me: "me", calendar: cal) }
    #expect(shows == [true, false, true, false, true, false, false, true, true])
    #expect(!GroupLines.showsSender(at: 99, in: list, me: "me"))
}

@Test func typingTextForOneAndMany() {
    #expect(GroupTyping.summary(names: []) == .none)
    #expect(GroupTyping.summary(names: ["Ana"]) == .one("Ana"))
    #expect(GroupTyping.summary(names: ["Ana", "Bo"]) == .many(2))
    #expect(GroupTyping.summary(names: ["a", "b", "c"]) == .many(3))
}

@Test func onlyCurrentUnblockedMembersTypeAndOnlyForTenSeconds() {
    let now: Int64 = 100_000
    let on: [String: Int64] = ["a": now - 1_000, "b": now - 11_000, "me": now, "x": now, "blocked": now]
    #expect(GroupTyping.typingNow(lastOnMs: on, members: ["a", "b", "me", "blocked"], me: "me", blocked: ["blocked"], nowMs: now) == ["a"])
}
