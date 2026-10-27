import Foundation

public struct ServerError: Error, Equatable, Sendable {
    public let status: Int
    public let code: String
    public let retryAfter: Int?
    public let details: [String: Int]?

    public init(status: Int, code: String, retryAfter: Int? = nil, details: [String: Int]? = nil) {
        self.status = status
        self.code = code
        self.retryAfter = retryAfter
        self.details = details
    }

    static let network = ServerError(status: 0, code: "network")
    static let cancelled = ServerError(status: 0, code: "cancelled")
    static func decoding() -> ServerError { ServerError(status: 0, code: "decoding") }
}

struct ErrorEnvelope: Decodable {
    struct Body: Decodable {
        let code: String
        let message: String?
        let details: [String: Int]?
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: Keys.self)
            code = try c.decode(String.self, forKey: .code)
            message = try? c.decodeIfPresent(String.self, forKey: .message)
            details = try? c.decodeIfPresent([String: Int].self, forKey: .details)
        }
        enum Keys: String, CodingKey { case code, message, details }
    }
    let error: Body
}
