import Foundation
import Testing
@testable import AppCore

@MainActor @Test func theSampleGroupInfoHasMembersAndRoles() async {
    let store = SampleChatStore(data: .make(now: Date()))
    let people = Array(store.contacts.prefix(2)).map(\.id)
    guard case .created(let chatID, _) = await store.createGroup(accounts: people, name: "Club", photo: nil),
          let info = store.groupInfo(chatID: chatID) else { Issue.record("no group"); return }
    #expect(info.name == "Club" && info.myRole == .owner && info.myState == .member && info.members.count == 3)
    #expect(info.members.filter { $0.role == .owner }.map(\.accountID) == [store.me.id])
}

@MainActor @Test func theSampleGroupFollowsEveryChange() async {
    let store = SampleChatStore(data: .make(now: Date()))
    let people = Array(store.contacts.prefix(3)).map(\.id)
    guard case .created(let chatID, _) = await store.createGroup(accounts: Array(people.prefix(2)), name: "Club", photo: nil) else { Issue.record("no group"); return }
    #expect(await store.addMembers(chatID: chatID, accounts: [people[2]]) == .done)
    #expect(store.groupInfo(chatID: chatID)?.members.count == 4)
    #expect(await store.setRole(chatID: chatID, account: people[0], role: .admin) == .done)
    #expect(store.groupInfo(chatID: chatID)?.members.first { $0.accountID == people[0] }?.role == .admin)
    #expect(await store.renameGroup(chatID: chatID, name: "Club 2") == .done)
    #expect(store.chat(id: chatID)?.title == "Club 2")
    #expect(await store.renameGroup(chatID: chatID, name: " ") == .failed(.invalidName))
    #expect(await store.removeMember(chatID: chatID, account: people[1]) == .done)
    #expect(store.groupInfo(chatID: chatID)?.members.count == 3)
    // The owner cannot leave without choosing a new one; after handing over they can, and the chat stays read-only.
    #expect(await store.leaveGroup(chatID: chatID) == .failed(.ownerMustTransfer))
    #expect(await store.setRole(chatID: chatID, account: people[0], role: .owner) == .done)
    #expect(store.groupInfo(chatID: chatID)?.myRole == .admin)
    #expect(await store.leaveGroup(chatID: chatID) == .done)
    #expect(store.groupInfo(chatID: chatID)?.myState == .left)
    #expect(await store.addMembers(chatID: chatID, accounts: [people[1]]) == .failed(.forbidden))
}

@MainActor @Test func anAdminCannotRemoveAnAdminOrAddOverTheLimit() async {
    let store = SampleChatStore(data: .make(now: Date()), memberGroup: true)
    let chatID = SampleChatStore.memberGroupChatID
    #expect(store.groupInfo(chatID: chatID)?.myRole == .member)
    #expect(await store.removeMember(chatID: chatID, account: "riya") == .failed(.forbidden))
    #expect(await store.addMembers(chatID: chatID, accounts: ["kiran"]) == .failed(.forbidden))
    #expect(await store.renameGroup(chatID: chatID, name: "Other") == .failed(.forbidden))
}
