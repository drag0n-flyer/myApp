import Foundation

/// A link to a person on this server: `https://<host>/u/<username>` (the QR code) or `https://<host>/c/<token>` (one
/// use). A query or fragment is ignored. Anything else, including a trailing slash or an extra segment, is not a link.
public enum ContactLink: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    case user(String)
    case invite(String)
    /// Shaped like one of ours but for another host: nothing is sent to that host.
    case otherServer
    case notALink

    public static let tokenLength = 22

    public static func parse(_ url: URL, serverHost: String) -> ContactLink {
        guard url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else { return .notALink }
        // Keeps empty pieces: "/u/alice/" and "//u/alice" are not links.
        // The raw path: `URL.path` drops a trailing slash and decodes "%2F".
        guard let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath else { return .notALink }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, parts[0].isEmpty else { return .notALink }
        let link: ContactLink
        switch parts[1] {
        case "u" where UsernameCheck.isValid(parts[2]): link = .user(parts[2])
        case "c" where isToken(parts[2]): link = .invite(parts[2])
        default: return .notALink
        }
        // The port counts, except the https default (443), which is the same as none.
        let authority = url.port.map { $0 == 443 ? host : "\(host):\($0)" } ?? host
        var mine = serverHost.trimmingCharacters(in: .whitespaces).lowercased()
        if mine.hasSuffix(":443") { mine.removeLast(4) }
        return authority.lowercased() == mine ? link : .otherServer
    }

    /// `https://<host>/u/<username>`, or `nil` for a name the rule refuses.
    public static func userURL(host: String, username: String) -> URL? {
        guard UsernameCheck.isValid(username) else { return nil }
        return URL(string: "https://\(host)/u/\(username)")
    }

    public static func inviteURL(host: String, token: String) -> URL? {
        guard isToken(token) else { return nil }
        return URL(string: "https://\(host)/c/\(token)")
    }

    private static func isToken(_ s: String) -> Bool {
        s.utf8.count == tokenLength && s.utf8.allSatisfy {
            ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2D || $0 == 0x5F
        }
    }

    /// Never shows the token, so a log line or a failed assertion cannot leak it.
    public var description: String {
        switch self {
        case .user(let name): "user(\(name))"
        case .invite: "invite(<token>)"
        case .otherServer: "otherServer"
        case .notALink: "notALink"
        }
    }
    public var debugDescription: String { description }
}
