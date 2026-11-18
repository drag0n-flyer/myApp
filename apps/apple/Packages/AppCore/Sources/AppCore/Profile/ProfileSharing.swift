import Foundation

/// Decisions for making the profile photo: a JPEG of at most 24,576 bytes. The image calls themselves (crop, scale,
/// encode) use system APIs in the app; every decision about sizes and the next attempt is here, so it can be tested.
public enum ProfilePhotoSizing {
    public static let maxBytes = 24_576

    /// One try: the side of the square in pixels and the JPEG quality (0...1).
    public struct Attempt: Equatable, Hashable, Sendable {
        public var side: Int
        public var quality: Double

        public init(side: Int, quality: Double) {
            self.side = side
            self.quality = quality
        }
    }

    public enum Decision: Equatable, Sendable {
        case accept
        case retry(Attempt)
        case giveUp
    }

    /// The square a photo is cut to.
    public struct Square: Equatable, Sendable {
        public var x: Int
        public var y: Int
        public var side: Int

        public init(x: Int, y: Int, side: Int) {
            self.x = x
            self.y = y
            self.side = side
        }
    }

    public static let first = Attempt(side: 256, quality: 0.8)

    static let qualitySteps: [Double] = [0.8, 0.7, 0.6, 0.5, 0.4, 0.3]
    static let sides: [Int] = [256, 192, 128, 96]

    /// The largest square in the middle of the picture; `nil` when the picture has no area.
    public static func centreSquare(width: Int, height: Int) -> Square? {
        guard width > 0, height > 0 else { return nil }
        let side = min(width, height)
        return Square(x: (width - side) / 2, y: (height - side) / 2, side: side)
    }

    /// What to do after encoding `attempt` produced `bytes`. Quality goes down first, then the picture gets
    /// smaller and starts again at a middle quality; when nothing is left the photo is given up on.
    public static func decision(after attempt: Attempt, bytes: Int) -> Decision {
        guard bytes > 0 else { return .giveUp }
        if bytes <= maxBytes { return .accept }
        if let lower = qualitySteps.first(where: { $0 < attempt.quality - 0.0001 }) {
            return .retry(Attempt(side: attempt.side, quality: lower))
        }
        if let smaller = sides.first(where: { $0 < attempt.side }) {
            return .retry(Attempt(side: smaller, quality: 0.7))
        }
        return .giveUp
    }
}

/// Which conversations get the own profile. Pure; the store does the sending.
public enum ProfileSender {
    public struct Target: Equatable, Sendable {
        public var chatID: String
        public var blocked: Bool
        public var isRequest: Bool
        public var isOneToOne: Bool

        public init(chatID: String, blocked: Bool, isRequest: Bool, isOneToOne: Bool) {
            self.chatID = chatID
            self.blocked = blocked
            self.isRequest = isRequest
            self.isOneToOne = isOneToOne
        }
    }

    /// The chats that have not yet been sent version `ownVer` (`sent` is the highest version sent per chat). A
    /// blocked contact, a message request (a stranger) and a group chat get nothing; nothing is sent before the
    /// first version exists. Sending again for the same version never happens, so a resend is harmless.
    public static func pending(chats: [Target], ownVer: Int, sent: [String: Int]) -> [String] {
        guard ownVer >= 1 else { return [] }
        return chats.filter { $0.isOneToOne && !$0.blocked && !$0.isRequest && (sent[$0.chatID] ?? 0) < ownVer }
            .map(\.chatID)
    }

    /// The version to publish: `nil` when the name is empty, or neither the name nor the photo changed.
    public static func nextVersion(current: Int?, publishedName: String?, name: String, photoChanged: Bool) -> Int? {
        guard !name.isEmpty, photoChanged || name != publishedName else { return nil }
        return (current ?? 0) + 1
    }
}

/// The one place that decides which name and photo to show for a person: a name the user saved locally (when that
/// exists), else the name the person sent in their profile, else the name from lookup.
public enum ProfileDisplay {
    public static func name(lookup: String, profile: StoredProfile?, nickname: String?) -> String {
        let nick = nickname.map { DisplayNameRule.forDisplay($0, fallback: "") } ?? ""
        if !nick.isEmpty { return nick }
        guard let profile else { return lookup }
        return DisplayNameRule.forDisplay(profile.name, fallback: lookup)
    }

    /// The stored photo, only when it is a JPEG within the limit (a stored value is checked again before drawing).
    public static func photo(profile: StoredProfile?) -> Data? {
        guard let photo = profile?.photo, photo.count <= ProfilePhotoSizing.maxBytes,
              photo.count >= 4, photo[photo.startIndex] == 0xFF, photo[photo.startIndex + 1] == 0xD8 else { return nil }
        return photo
    }
}
