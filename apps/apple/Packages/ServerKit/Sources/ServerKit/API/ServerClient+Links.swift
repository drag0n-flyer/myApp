import AppCore
import Foundation

/// Why "Invite with a link" gave no link.
public enum NewLinkFailure: Error, Equatable, Sendable {
    /// 20 unused links already: "You have too many unused links. Wait for one to expire."
    case tooMany
    case failed
}

extension ServerClient {
    /// Redeems a one-time link, once. The caller drops the token afterwards.
    public func redeemLink(token: String) async throws(LinkRedeemFailure) -> RedeemedPerson {
        do {
            let who = try await redeemContactLink(token: token)
            return RedeemedPerson(accountID: who.accountID, username: who.username, displayName: who.displayName)
        } catch {
            switch error.code {
            case "own_link": throw .ownLink
            case "expired": throw .expired
            default: throw .tryAgain
            }
        }
    }

    /// Makes a one-time link and returns the URL to share (`https://<host>/c/<token>`).
    public func newInviteLinkURL(host: String) async throws(NewLinkFailure) -> URL {
        let link: NewContactLink
        do { link = try await createContactLink() } catch {
            throw error.code == "too_many" ? .tooMany : .failed
        }
        guard let url = ContactLink.inviteURL(host: host, token: link.token) else { throw .failed }
        return url
    }
}
