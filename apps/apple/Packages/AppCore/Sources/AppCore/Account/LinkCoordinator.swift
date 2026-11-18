import Foundation
import Observation

/// Who a one-time link belongs to, as the server tells it on redeem.
public struct RedeemedPerson: Equatable, Sendable {
    public let accountID: String
    public let username: String
    public let displayName: String

    public init(accountID: String, username: String, displayName: String) {
        self.accountID = accountID
        self.username = username
        self.displayName = displayName
    }
}

/// Why a redeem did not give a person.
public enum LinkRedeemFailure: Error, Equatable, Sendable {
    /// The person's own link (the server leaves it unused).
    case ownLink
    /// Unknown, malformed, expired, used, owner gone or owner blocked the caller: all alike.
    case expired
    /// Rate limited, offline or any other failure.
    case tryAgain
}

/// What the app tells the person about an opened link, in the spec's words (6.5).
public enum LinkMessage: Equatable, Sendable {
    case ownLink, expired, otherServer, tryAgain

    public var text: String {
        switch self {
        case .ownLink: "This is your own link."
        case .expired: "This link has expired. Ask for a new one."
        case .otherServer: "This link is for a different server."
        case .tryAgain: "Couldn't open the link. Try again in a moment."
        }
    }
}

/// What opening a link leads to.
public enum LinkOutcome: Equatable, Sendable {
    /// Ask "Add this person?"; the name is known only after a one-time link was redeemed.
    case confirmAdd(username: String, displayName: String?)
    /// Already a contact: open the chat.
    case openChat(contactID: String)
    case message(LinkMessage)
}

/// What the coordinator needs from the app. Main actor: the app model answers from its store.
@MainActor
public protocol LinkServices: AnyObject {
    var ownUsername: String { get }
    /// The host of the server the person is on now (the signed-in one, else the one being joined). Asked when a link is
    /// handled, not when it arrived.
    var linkHost: String { get }
    /// The contact with this username, if any.
    func contactID(username: String) -> String?
    /// Redeems a one-time link's token. The caller drops the token afterwards.
    func redeem(token: String) async throws(LinkRedeemFailure) -> RedeemedPerson
}

/// Holds the one link that is waiting and handles it once the app can: unlocked, signed in and past onboarding.
///
/// A link opened while locked or signed out is kept (one only: the latest wins) and handled once, by `process(ready:)`.
/// A host other than the server's is never contacted. The token of a one-time link lives only in the pending link and
/// is dropped when `process` takes it, before the redeem call; nothing here prints it.
@MainActor
@Observable
public final class LinkCoordinator {
    /// What the screen should show now; `dismiss()` clears it.
    public private(set) var outcome: LinkOutcome?
    /// Counts the links received, so a view can watch for a new one.
    public private(set) var received = 0
    @ObservationIgnored private var pending: URL?
    @ObservationIgnored private var working = false
    @ObservationIgnored private var generation = 0

    public init() {}

    public var hasPending: Bool { pending != nil }

    /// Keeps the URL if it has the shape of one of our links, whatever its host; the latest wins. Anything else changes
    /// nothing. The host is judged later, in `process`, against the server the person is on by then.
    public func receive(_ url: URL) {
        guard ContactLink.parse(url, serverHost: url.host ?? "") != .notALink else { return }
        pending = url
        received += 1
    }

    /// Handles the waiting link while `ready()` (asked again on every pass), once. A link that arrives meanwhile waits
    /// until the outcome on screen is dismissed. A result that comes back after `clear()` is discarded.
    public func process(ready: @MainActor () -> Bool, services: any LinkServices) async {
        guard !working else { return }
        working = true
        defer { working = false }
        while ready(), outcome == nil, let url = pending {
            pending = nil
            let started = generation
            let result = await resolve(ContactLink.parse(url, serverHost: services.linkHost), services)
            guard started == generation else { continue }
            outcome = result
        }
    }

    public func dismiss() { outcome = nil }

    /// Drops a waiting link and what is shown (sign-out); a redeem still out is discarded when it returns.
    public func clear() {
        generation += 1
        pending = nil
        outcome = nil
    }

    private func resolve(_ link: ContactLink, _ services: any LinkServices) async -> LinkOutcome? {
        switch link {
        case .notALink: return nil
        case .otherServer: return .message(.otherServer)
        case .user(let name):
            if name == services.ownUsername { return .message(.ownLink) }
            return known(name, displayName: nil, services)
        case .invite(let token):
            do throws(LinkRedeemFailure) {
                let person = try await services.redeem(token: token)
                if person.username == services.ownUsername { return .message(.ownLink) }
                return known(person.username, displayName: person.displayName, services)
            } catch {
                switch error {
                case .ownLink: return .message(.ownLink)
                case .expired: return .message(.expired)
                case .tryAgain: return .message(.tryAgain)
                }
            }
        }
    }

    private func known(_ username: String, displayName: String?, _ services: any LinkServices) -> LinkOutcome {
        if let id = services.contactID(username: username) { return .openChat(contactID: id) }
        return .confirmAdd(username: username, displayName: displayName)
    }
}
