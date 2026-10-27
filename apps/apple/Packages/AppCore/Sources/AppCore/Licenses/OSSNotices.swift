import Foundation

/// One open-source component and its license notice.
public struct OSSComponent: Codable, Identifiable, Hashable, Sendable {
    public let name: String
    public let version: String
    /// SPDX license identifier or expression.
    public let license: String
    public let text: String
    /// True while the full license text is still to come with the component's first release.
    public let pending: Bool

    public var id: String { "\(name) \(version)" }

    public init(name: String, version: String, license: String, text: String, pending: Bool = false) {
        self.name = name
        self.version = version
        self.license = license
        self.text = text
        self.pending = pending
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        version = try c.decode(String.self, forKey: .version)
        license = try c.decode(String.self, forKey: .license)
        text = try c.decode(String.self, forKey: .text)
        pending = try c.decodeIfPresent(Bool.self, forKey: .pending) ?? false
    }
}

/// Loads the notices that `scripts/generate-oss-notices.py` writes to `Resources/oss-notices.json`.
public enum OSSNotices {
    private struct File: Decodable { let components: [OSSComponent] }

    public static func decode(_ data: Data) throws -> [OSSComponent] {
        try JSONDecoder().decode(File.self, from: data).components
    }

    public static func bundled() throws -> [OSSComponent] {
        guard let url = Bundle.module.url(forResource: "oss-notices", withExtension: "json") else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try decode(Data(contentsOf: url))
    }
}
