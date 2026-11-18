import AppCore
import Foundation
import LocalStore

extension ContactNames {
    /// The name of a member of a group: the contact's name (or the cleaned lookup name of someone known only from a
    /// group), else the name they sent in their profile, else `unknown`. Never an account ID.
    static func groupMember(account: String, repo: Repository, unknown: String) throws -> String {
        let row = try repo.contact(id: account)
        let lookup = row.map { DisplayNameRule.forDisplay($0.displayName, fallback: $0.username) } ?? ""
        let profile = try repo.profile(for: account).map { StoredProfile(accountID: $0.accountID, name: $0.name, photo: $0.photo, ver: $0.ver) }
        let name = ProfileDisplay.name(lookup: lookup, profile: profile, nickname: nil)
        return name.isEmpty ? unknown : name
    }
}
