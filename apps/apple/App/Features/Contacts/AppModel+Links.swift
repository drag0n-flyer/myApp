import AppCore
import Foundation
import ServerKit

/// The one coordinator for links opened from outside (universal link, paste, scan). Process lifetime; `AppModel.swift`
/// belongs to other tasks, so it lives here.
@MainActor
enum AppLinks {
    static let coordinator = LinkCoordinator()
}

extension AppModel: LinkServices {
    /// Whether a waiting link may be handled now: the lock cover is down and onboarding is done (signed in).
    var linkReady: Bool { !lock.coversContent && onboarding.step == .done }

    var ownUsername: String { me.username }

    func contactID(username: String) -> String? {
        contacts.first { $0.username == username }?.id
    }

    func receiveContactLink(_ url: URL) {
        AppLinks.coordinator.receive(url, serverHost: server.host)
    }

    func processLinks() async {
        await AppLinks.coordinator.process(ready: linkReady, services: self)
    }

    /// Redeems a one-time link. The token is not kept: it exists only in this call. A sample account has no server, so
    /// it answers with a fixed person.
    func redeem(token: String) async throws(LinkRedeemFailure) -> RedeemedPerson {
        if isSampleData { return RedeemedPerson(accountID: "neha", username: "neha", displayName: "Neha") }
        guard let client = contactLinkClient else { throw .tryAgain }
        return try await client.redeemLink(token: token)
    }

    /// `https://<host>/u/<username>` for the QR code.
    var myProfileLinkURL: URL? { ContactLink.userURL(host: server.host, username: me.username) }

    /// Makes a one-time link to share. A sample account gets a link that goes nowhere.
    func makeInviteLink() async -> Result<URL, NewLinkFailure> {
        if isSampleData {
            guard let url = ContactLink.inviteURL(host: server.host, token: "SampleSampleSampleSamp") else { return .failure(.failed) }
            return .success(url)
        }
        guard let client = contactLinkClient else { return .failure(.failed) }
        do throws(NewLinkFailure) { return .success(try await client.newInviteLinkURL(host: server.host)) } catch { return .failure(error) }
    }
}
