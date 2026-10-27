import Foundation

/// `https://<host>/invite/<code>`: which server to join and the invite code to use.
public struct InviteLink: Equatable, Sendable {
    public var host: String
    public var code: String

    public init(host: String, code: String) {
        self.host = host
        self.code = code
    }

    public static func parse(_ url: URL) -> InviteLink? {
        guard url.scheme == "https", let host = url.host, !host.isEmpty else { return nil }
        let parts = url.path.split(separator: "/")
        guard parts.count == 2, parts[0] == "invite" else { return nil }
        let code = String(parts[1])
        let allowed = code.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
        guard (6...32).contains(code.count), allowed else { return nil }
        return InviteLink(host: host, code: code)
    }
}
