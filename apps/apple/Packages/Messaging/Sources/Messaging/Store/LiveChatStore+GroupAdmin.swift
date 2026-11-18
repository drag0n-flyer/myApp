import AppCore
import Foundation
import LocalStore
import ServerKit

extension GroupChangeFailure {
    /// What the screen is told for an error from changing a group (see `ConversationManager+GroupSync`).
    public init(error: Error) {
        switch error {
        case GroupAdminError.forbidden: self = .forbidden
        case GroupAdminError.ownerMustTransfer: self = .ownerMustTransfer
        case GroupAdminError.full: self = .full
        case GroupAdminError.invalidName: self = .invalidName
        case GroupAdminError.invalidPhoto: self = .invalidPhoto
        case GroupAdminError.notGroup, GroupAdminError.unknownMember: self = .notFound
        case let server as ServerError:
            switch server.code {
            case "forbidden": self = .forbidden
            case "owner_must_transfer": self = .ownerMustTransfer
            case "group_full": self = .full
            case "not_found": self = .notFound
            case "rate_limited": self = .rateLimited
            default: self = server.status == 0 || server.status >= 500 || server.status == 408 ? .offline : .other
            }
        default: self = .other
        }
    }
}

extension LiveChatStore {
    public func groupMembers(chatID: String) -> [GroupMember] {
        ((try? deps.repo.groupMembers(chatID: chatID)) ?? []).map { GroupMember(accountID: $0.accountID, role: GroupRole(wire: $0.role)) }
    }

    /// Runs one change, then sends what it queued and refreshes whatever happened.
    private func groupChange(_ work: () async throws -> GroupChangeOutcome) async -> GroupChangeOutcome {
        guard !isClosed else { return .failed(.other) }
        defer { refresh() }
        do {
            let outcome = try await work()
            flushOutboxSoon()
            return outcome
        } catch {
            // A change that failed half way (the server answered, the MLS step did not) is finished by the next reconcile.
            flushOutboxSoon()
            return .failed(GroupChangeFailure(error: error))
        }
    }

    public func addMembers(chatID: String, accounts: [String]) async -> GroupChangeOutcome {
        let manager = conversations
        return await groupChange { .added(notAdded: try await manager.addGroupMembers(chatID: chatID, accounts: accounts)) }
    }

    public func removeMember(chatID: String, account: String) async -> GroupChangeOutcome {
        let manager = conversations
        return await groupChange { try await manager.removeGroupMember(chatID: chatID, account: account); return .done }
    }

    public func leaveGroup(chatID: String) async -> GroupChangeOutcome {
        let manager = conversations
        return await groupChange { try await manager.leaveGroup(chatID: chatID); return .done }
    }

    public func setRole(chatID: String, account: String, role: GroupRole) async -> GroupChangeOutcome {
        let manager = conversations
        return await groupChange { try await manager.setGroupRole(chatID: chatID, account: account, role: role); return .done }
    }

    public func renameGroup(chatID: String, name: String) async -> GroupChangeOutcome {
        let manager = conversations
        return await groupChange { try await manager.changeGroupInfo(chatID: chatID, name: name, photo: nil, setPhoto: false); return .done }
    }

    public func setGroupPhoto(chatID: String, photo: Data?) async -> GroupChangeOutcome {
        let manager = conversations
        return await groupChange { try await manager.changeGroupInfo(chatID: chatID, name: nil, photo: photo, setPhoto: true); return .done }
    }
}
