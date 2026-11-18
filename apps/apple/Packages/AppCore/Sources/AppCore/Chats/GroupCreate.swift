import Foundation

/// Who may be in a new group and what it must be called (spec 6.7: 2 to 99 other people; a name of 1 to 50 code points).
public enum GroupCreateRules {
    public static let minPeople = 2
    public static let maxPeople = 99

    /// "Choose at least 2 people" shows while this is true.
    public static func needsMorePeople(selected: Int) -> Bool { selected < minPeople }

    public static func canCreate(selected: Int, name: String) -> Bool {
        selected >= minPeople && selected <= maxPeople && GroupNameRule.clean(name) != nil
    }
}

/// Why a group was not made.
public enum GroupCreateFailure: Equatable, Sendable {
    case invalidName, invalidPhoto, tooFewPeople, tooManyPeople
    /// The server does not know one of the people.
    case notFound
    /// The group would be over the limit.
    case full
    /// Nobody chosen could be added (no way to reach their devices yet).
    case noKeyPackages
    case rateLimited
    /// A network or server problem: trying again later may work.
    case offline
    case other
}

public enum GroupCreateOutcome: Equatable, Sendable {
    /// `notAdded` are the account IDs that are not in yet ("%@ couldn't be added yet").
    case created(chatID: String, notAdded: [String])
    case failed(GroupCreateFailure)
}

/// Making group chats. A separate protocol so stores that cannot (and the tests' own stores) keep working.
@MainActor
public protocol GroupStore: AnyObject {
    /// Makes a group of `accounts` (2 to 99 other people). `photo` is a JPEG of 4 to 24,576 bytes, or `nil`.
    func createGroup(accounts: [String], name: String, photo: Data?) async -> GroupCreateOutcome
}

extension GroupStore {
    public func createGroup(accounts: [String], name: String, photo: Data?) async -> GroupCreateOutcome { .failed(.other) }
}
