public struct ChatFolder: Identifiable, Hashable, Codable, Sendable {
    public enum Kind: Hashable, Codable, Sendable {
        case all, unread, custom
    }

    public var id: String
    public var name: String
    public var kind: Kind

    public init(id: String, name: String, kind: Kind) {
        self.id = id
        self.name = name
        self.kind = kind
    }

    public static let all = ChatFolder(id: "all", name: "All", kind: .all)
    public static let unread = ChatFolder(id: "unread", name: "Unread", kind: .unread)
}
