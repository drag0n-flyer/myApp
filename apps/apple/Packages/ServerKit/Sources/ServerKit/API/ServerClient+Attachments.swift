import Foundation

extension ServerClient {
    /// `data` is already encrypted by the caller.
    /// `progress` is told how much of `data` has gone out (0...1) where the transport can tell.
    public func uploadAttachment(_ data: Data, progress: (@Sendable (Double) -> Void)? = nil) async throws(ServerError) -> UploadedAttachment {
        let (body, _) = try await requestRaw("POST", "/v1/attachments", body: data, contentType: "application/octet-stream",
                                             headers: ["Content-Length": String(data.count)], auth: .session, progress: progress)
        do { return try ServerJSON.decoder().decode(UploadedAttachment.self, from: body) } catch { throw ServerError.decoding() }
    }

    public func downloadAttachment(id: String, range: ClosedRange<Int>? = nil) async throws(ServerError) -> Data {
        var headers = ["Accept": "application/octet-stream"]
        if let range { headers["Range"] = "bytes=\(range.lowerBound)-\(range.upperBound)" }
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? id
        let (data, _) = try await requestRaw("GET", "/v1/attachments/\(escaped)", headers: headers, auth: .session)
        return data
    }
}
