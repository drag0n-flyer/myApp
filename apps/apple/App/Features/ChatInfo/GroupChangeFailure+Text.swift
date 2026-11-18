import AppCore
import Foundation

extension GroupChangeFailure {
    /// What the screen says when a change to a group did not happen. Plain words, from the String Catalog.
    var text: String {
        switch self {
        case .forbidden, .notFound: String(localized: "You can't do that in this group.")
        case .ownerMustTransfer: String(localized: "Choose a new owner before you leave.")
        case .full: String(localized: "This group is full.")
        case .invalidName: String(localized: "Choose a different name.")
        case .invalidPhoto: String(localized: "Choose a different photo.")
        case .rateLimited, .offline, .other: String(localized: "Couldn't change the group. Check your connection and try again.")
        }
    }
}
