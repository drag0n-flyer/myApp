import Foundation

/// One open-source component. Its license texts live in `OSSNoticeBook.texts`, by id.
public struct OSSComponent: Codable, Identifiable, Hashable, Sendable {
    public let name: String
    public let version: String
    /// SPDX license identifier or expression.
    public let license: String
    /// Copyright holders or authors, as the component declares them. May be empty.
    public let authors: String
    public let repository: String?
    /// Ids into `OSSNoticeBook.texts`: the canonical license text, then the component's own file.
    public let textIds: [String]
    /// True while the full license text is still to come with the component's first release.
    public let pending: Bool

    public var id: String { "\(name) \(version)" }

    public init(name: String, version: String, license: String, authors: String = "",
                repository: String? = nil, textIds: [String] = [], pending: Bool = false) {
        self.name = name
        self.version = version
        self.license = license
        self.authors = authors
        self.repository = repository
        self.textIds = textIds
        self.pending = pending
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        version = try c.decode(String.self, forKey: .version)
        license = try c.decode(String.self, forKey: .license)
        authors = try c.decodeIfPresent(String.self, forKey: .authors) ?? ""
        repository = try c.decodeIfPresent(String.self, forKey: .repository)
        textIds = try c.decodeIfPresent([String].self, forKey: .textIds) ?? []
        pending = try c.decodeIfPresent(Bool.self, forKey: .pending) ?? false
    }
}

/// All components plus the license texts they share.
public struct OSSNoticeBook: Decodable, Sendable {
    public let components: [OSSComponent]
    public let texts: [String: String]

    /// The full license text of a component: every referenced text, separated by a rule.
    public func text(for component: OSSComponent) -> String {
        component.textIds.compactMap { texts[$0] }.joined(separator: "\n\n----\n\n")
    }
}

/// Loads the notices that `scripts/generate-oss-notices.py` writes to `Resources/oss-notices.json`.
public enum OSSNotices {
    public static func decode(_ data: Data) throws -> OSSNoticeBook {
        try JSONDecoder().decode(OSSNoticeBook.self, from: data)
    }

    public static func bundled() throws -> OSSNoticeBook {
        guard let url = Bundle.module.url(forResource: "oss-notices", withExtension: "json") else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try decode(Data(contentsOf: url))
    }
}
