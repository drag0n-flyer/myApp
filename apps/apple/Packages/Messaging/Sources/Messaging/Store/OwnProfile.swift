import AppCore
import Foundation
import LocalStore

/// The own profile in the settings store, shared by the store (sending) and the incoming handler (adopting what
/// another device of mine says): `profile.ver`, `profile.name` and `profile.photo.published` (what the last version
/// said), `profile.photo` (the current photo; empty = none), `profile.sent:<chat>` (the highest version queued for
/// that chat).
enum OwnProfile {
    static let verKey = "profile.ver"
    static let nameKey = "profile.name"
    static let photoKey = "profile.photo"
    static let publishedPhotoKey = "profile.photo.published"
    static let sentPrefix = "profile.sent:"

    static func sentKey(_ chatID: String) -> String { sentPrefix + chatID }

    static func int(_ data: Data?) -> Int? { data.flatMap { Int(String(decoding: $0, as: UTF8.self)) } }

    static func storedVersion(_ repo: Repository) -> Int {
        int((try? repo.get(verKey)) ?? nil) ?? 0
    }

    /// A `profile` that came from another device of my own account. It replaces my stored profile (name and photo,
    /// after the photo rule) only with a strictly higher version, and marks every chat as sent at that version: the
    /// device that made the change sends it to the chats, this one sends nothing.
    @discardableResult
    static func adopt(name: String, photo: Data?, ver: Int, repo: Repository, me: String) throws -> Bool {
        guard DisplayNameRule.clean(name) == name, photo.map(ProfilePhotoRule.isAcceptable) ?? true,
              ProfileMerge.accept(current: storedVersion(repo) == 0 ? nil : storedVersion(repo), incoming: ver) else { return false }
        try repo.transaction {
            try repo.set(verKey, Data(String(ver).utf8))
            try repo.set(nameKey, Data(name.utf8))
            try repo.set(photoKey, photo ?? Data())
            try repo.set(publishedPhotoKey, photo ?? Data())
            if var row = try repo.contact(id: me) {
                row.displayName = name
                try repo.upsertContact(row)
            }
            for chat in try repo.chats() { try repo.set(sentKey(chat.id), Data(String(ver).utf8)) }
        }
        return true
    }
}

/// The name shown for a stored contact row, by the one rule: a name the user saved (none yet), the name the person
/// sent in their profile (only once they are a contact, not while they are a message request), then the lookup name.
enum ContactNames {
    static func display(_ row: ContactRecord, repo: Repository) -> String {
        let lookup = DisplayNameRule.forDisplay(row.displayName, fallback: row.username)
        let profile = row.isContact ? ((try? repo.profile(for: row.accountID)) ?? nil).map {
            StoredProfile(accountID: $0.accountID, name: $0.name, photo: $0.photo, ver: $0.ver)
        } : nil
        return ProfileDisplay.name(lookup: lookup, profile: profile, nickname: nil)
    }
}
