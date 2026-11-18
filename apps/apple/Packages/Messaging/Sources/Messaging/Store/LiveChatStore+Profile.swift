import AppCore
import Foundation
import LocalStore

/// The own profile: the photo, the version, and sending it to the one-to-one chats. The own name is the own contact
/// row's name (`setMyDisplayName`). The keys are in `OwnProfile`.
extension LiveChatStore {
    static func profileSentKey(_ chatID: String) -> String { OwnProfile.sentKey(chatID) }

    /// The own photo, checked again before it is drawn (a row written by an earlier build is not trusted).
    public var myPhoto: Data? {
        guard let data = (try? deps.repo.get(OwnProfile.photoKey)) ?? nil, ProfilePhotoRule.isAcceptable(data) else { return nil }
        return data
    }

    /// Stored when it passes `ProfilePhotoRule` (or `nil`, to remove it); the new profile goes out at once.
    @discardableResult
    public func setMyPhoto(_ photo: Data?) -> Bool {
        if let photo, !ProfilePhotoRule.isAcceptable(photo) { return false }
        guard (try? deps.repo.set(OwnProfile.photoKey, photo ?? Data())) != nil else { return false }
        refresh()
        return true
    }

    /// The photo of a contact (from their `profile`, once they are a contact) or the own one; nothing when it does
    /// not pass `ProfilePhotoRule`.
    public func profilePhoto(for accountID: String) -> Data? {
        if accountID == deps.me.accountID { return myPhoto }
        if let row = profileRows[accountID] { return ProfileDisplay.photo(profile: row) }
        // A tile is shown without its chat: a group member's photo only when no message request from them is open.
        if ((try? deps.repo.chat(id: accountID)) ?? nil)?.isRequest == true { return nil }
        return ProfileDisplay.photo(profile: groupProfileRows[accountID])
    }

    /// Publishes a new version when the own name or photo is not what the last version said, then queues the current
    /// version once in every chat that has not had it: not for a blocked contact or a message request; a group only while this device is a member.
    /// The "sent" marker is written in the same transaction as the queued message, so a queue that fails leaves the
    /// chat pending for the next pass. Calling it again changes nothing.
    public func sendOwnProfileIfNeeded() {
        let repo = deps.repo
        let me = deps.me.accountID
        guard let row = (try? repo.contact(id: me)) ?? nil else { return }
        var ver = OwnProfile.storedVersion(repo)
        var name = (try? repo.get(OwnProfile.nameKey)).flatMap { $0 }.flatMap { String(data: $0, encoding: .utf8) }
        var photo = (try? repo.get(OwnProfile.publishedPhotoKey)) ?? Data()
        let current = (try? repo.get(OwnProfile.photoKey)) ?? Data()
        let now = Int(deps.now().timeIntervalSince1970)
        if let cleaned = DisplayNameRule.clean(row.displayName),
           let next = ProfileSender.nextVersion(
               current: ver >= 1 ? ver : nil, publishedName: name, name: cleaned, photoChanged: current != photo, nowSeconds: now) {
            // A stored photo that no longer passes the rule is not published.
            let publish = ProfilePhotoRule.isAcceptable(current) ? current : Data()
            let wrote = try? repo.transaction { () throws -> Void in
                try repo.set(OwnProfile.verKey, Data(String(next).utf8))
                try repo.set(OwnProfile.nameKey, Data(cleaned.utf8))
                try repo.set(OwnProfile.publishedPhotoKey, publish)
            }
            guard wrote != nil else { return }
            ver = next; name = cleaned; photo = publish
        }
        guard ver >= 1, let name else { return }
        let blocked = Set((try? repo.blockedIDs()) ?? [])
        let targets = ((try? repo.chats()) ?? []).map {
            ProfileSender.Target(chatID: $0.id, blocked: blocked.contains($0.id), isRequest: $0.isRequest,
                                 // A group this device is still in gets it too (its members need not know me otherwise).
                                 isOneToOne: !$0.id.hasPrefix("g:") || ($0.kind == "group" && $0.myState == "member"))
        }
        let sent = ((try? repo.entries(prefix: OwnProfile.sentPrefix)) ?? [:]).reduce(into: [String: Int]()) {
            $0[String($1.key.dropFirst(OwnProfile.sentPrefix.count))] = OwnProfile.int($1.value)
        }
        let enqueue = profileEnqueueOverride ?? { [outbox] body, chatID in try outbox.enqueueControl(body, chatID: chatID) }
        let body = ContentEnvelope.Body.profile(name: name, photo: photo.isEmpty ? nil : photo, ver: ver)
        var queued = false
        for chatID in ProfileSender.pending(chats: targets, ownVer: ver, sent: sent) {
            let done: Void? = try? repo.transaction { () throws -> Void in
                try enqueue(body, chatID)
                try repo.set(OwnProfile.sentKey(chatID), Data(String(ver).utf8))
            }
            if done != nil { queued = true }
        }
        if queued { flushOutboxSoon() }
    }
}
