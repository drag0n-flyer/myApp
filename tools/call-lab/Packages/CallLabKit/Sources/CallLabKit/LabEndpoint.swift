import Foundation

/// Server URLs from the Connect screen's host field (bare host means https/wss).
public enum LabEndpoint {
    private static func base(_ host: String) -> (secure: Bool, rest: String)? {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !h.isEmpty else { return nil }
        var secure = true
        if h.hasPrefix("http://") { secure = false; h.removeFirst(7) }
        else if h.hasPrefix("https://") { h.removeFirst(8) }
        while h.hasSuffix("/") { h.removeLast() }
        return h.isEmpty ? nil : (secure, h)
    }

    public static func wsURL(host: String) -> URL? {
        base(host).flatMap { URL(string: "\($0.secure ? "wss" : "ws")://\($0.rest)/v1/ws") }
    }

    public static func turnURL(host: String) -> URL? {
        base(host).flatMap { URL(string: "\($0.secure ? "https" : "http")://\($0.rest)/v1/turn") }
    }
}
