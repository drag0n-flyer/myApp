import AppCore
import Foundation
import LocalStore

/// The own profile: the photo, the version, and sending it to the one-to-one chats. The own name is the own contact
/// row's name (`setMyDisplayName`). Kept in the settings store: `profile.ver`, `profile.name` and `profile.photo.published`
/// (what the last version said), `profile.photo` (the current photo; empty = none), `profile.sent:<chat>` (the highest
/// version queued for that chat).
extension LiveChatStore {
    static let profileVerKey = "profile.ver"
    static let profileNameKey = "profile.name"
    static let profilePhotoKey = "profile.photo"
    static let profilePublishedPhotoKey = "profile.photo.published"
    static let profileSentPrefix = "profile.sent:"

    static func profileSentKey(_ chatID: String) -> String { profileSentPrefix + chatID }

    private static func int(_ data: Data?) -> Int? { data.flatMap { Int(String(decoding: $0, as: UTF8.self)) } }

    public var myPhoto: Data? {
        guard let data = (try? deps.repo.get(Self.profilePhotoKey)) ?? nil, !data.isEmpty else { return nil }
        return data
    }

    /// Stored when it is a JPEG within the limit (or `nil`, to remove it); the new profile goes out at once.
    @discardableResult
    public func setMyPhoto(_ photo: Data?) -> Bool {
        if let photo, !LinkPreview.isJPEG(photo, max: ContentEnvelope.maxProfilePhoto) { return false }
        guard (try? deps.repo.set(Self.profilePhotoKey, photo ?? Data())) != nil else { return false }
        refresh()
        return true
    }

    /// The photo of a contact (from their `profile`) or the own one; nothing when it is not a JPEG within the limit.
    public func profilePhoto(for accountID: String) -> Data? {
        if accountID == deps.me.accountID { return myPhoto }
        return ProfileDisplay.photo(profile: profileRows[accountID])
    }

    /// Publishes a new version when the own name or photo is not what the last version said, then queues the current
    /// version once in every chat that has not had it: not for a blocked contact, a message request or a group.
    /// Calling it again changes nothing.
    public func sendOwnProfileIfNeeded() {
        let repo = deps.repo
        let me = deps.me.accountID
        guard let row = (try? repo.contact(id: me)) ?? nil else { return }
        var ver = Self.int(try? repo.get(Self.profileVerKey)) ?? 0
        var name = (try? repo.get(Self.profileNameKey)).flatMap { String(data: $0, encoding: .utf8) }
        var photo = (try? repo.get(Self.profilePublishedPhotoKey)) ?? Data()
        let current = (try? repo.get(Self.profilePhotoKey)) ?? Data()
        if let cleaned = DisplayNameRule.clean(row.displayName),
           let next = ProfileSender.nextVersion(
               current: ver >= 1 ? ver : nil, publishedName: name, name: cleaned, photoChanged: current != photo) {
            let wrote = try? repo.transaction { () throws -> Void in
                try repo.set(Self.profileVerKey, Data(String(next).utf8))
                try repo.set(Self.profileNameKey, Data(cleaned.utf8))
                try repo.set(Self.profilePublishedPhotoKey, current)
            }
            guard wrote != nil else { return }
            ver = next; name = cleaned; photo = current
        }
        guard ver >= 1, let name else { return }
        let blocked = Set((try? repo.blockedIDs()) ?? [])
        let targets = ((try? repo.chats()) ?? []).map {
            ProfileSender.Target(chatID: $0.id, blocked: blocked.contains($0.id), isRequest: $0.isRequest, isOneToOne: !$0.id.hasPrefix("g:"))
        }
        let sent = ((try? repo.entries(prefix: Self.profileSentPrefix)) ?? [:]).reduce(into: [String: Int]()) {
            $0[String($1.key.dropFirst(Self.profileSentPrefix.count))] = Self.int($1.value)
        }
        for chatID in ProfileSender.pending(chats: targets, ownVer: ver, sent: sent) {
            // The marker comes first: a write that fails sends nothing, so a failure never makes a loop of sends.
            guard (try? repo.set(Self.profileSentKey(chatID), Data(String(ver).utf8))) != nil else { continue }
            sendControl(.profile(name: name, photo: photo.isEmpty ? nil : photo, ver: ver), chatID: chatID)
        }
    }
}
