import AppCore
import Foundation

/// What the group screens ask of the store. Each change bumps `revision` so the screens read the new state.
extension AppModel {
    func groupInfo(chatID: String) -> GroupInfo? { _ = revision; return chatStore.groupInfo(chatID: chatID) }

    func addMembers(chatID: String, accounts: [String]) async -> GroupChangeOutcome {
        defer { bumpRevision() }
        return await chatStore.addMembers(chatID: chatID, accounts: accounts)
    }

    func removeMember(chatID: String, account: String) async -> GroupChangeOutcome {
        defer { bumpRevision() }
        return await chatStore.removeMember(chatID: chatID, account: account)
    }

    func leaveGroup(chatID: String) async -> GroupChangeOutcome {
        defer { bumpRevision() }
        return await chatStore.leaveGroup(chatID: chatID)
    }

    func setRole(chatID: String, account: String, role: GroupRole) async -> GroupChangeOutcome {
        defer { bumpRevision() }
        return await chatStore.setRole(chatID: chatID, account: account, role: role)
    }

    func renameGroup(chatID: String, name: String) async -> GroupChangeOutcome {
        defer { bumpRevision() }
        return await chatStore.renameGroup(chatID: chatID, name: name)
    }

    func setGroupPhoto(chatID: String, photo: Data?) async -> GroupChangeOutcome {
        defer { bumpRevision() }
        return await chatStore.setGroupPhoto(chatID: chatID, photo: photo)
    }

    func deleteChat(chatID: String) {
        chatStore.deleteChat(chatID: chatID)
        bumpRevision()
    }

}
