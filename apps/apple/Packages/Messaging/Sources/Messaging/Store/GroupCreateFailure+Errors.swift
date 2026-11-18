import AppCore
import Foundation
import ServerKit

extension GroupCreateFailure {
    /// What the screen is told for an error from making a group (see `ConversationManager.createGroup`).
    public init(error: Error) {
        switch error {
        case GroupCreateError.invalidName: self = .invalidName
        case GroupCreateError.invalidPhoto: self = .invalidPhoto
        case GroupCreateError.tooFewPeople: self = .tooFewPeople
        case GroupCreateError.tooManyPeople: self = .tooManyPeople
        case SyncError.noKeyPackages: self = .noKeyPackages
        case let server as ServerError:
            switch server.code {
            case "not_found": self = .notFound
            case "group_full": self = .full
            case "rate_limited": self = .rateLimited
            default: self = server.status == 0 || server.status >= 500 || server.status == 408 ? .offline : .other
            }
        default: self = .other
        }
    }
}
