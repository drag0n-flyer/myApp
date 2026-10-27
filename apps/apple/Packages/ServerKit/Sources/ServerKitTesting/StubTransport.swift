import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import ServerKit

public actor StubTransport: HTTPTransport {
    private enum Reply {
        case response(status: Int, body: Data, headers: [String: String])
        case failure(URLError)
    }

    private var routes: [String: Reply] = [:]
    public private(set) var requests: [URLRequest] = []

    public init() {}

    public func respond(_ method: String, _ path: String, status: Int, json: String, headers: [String: String] = [:]) {
        routes["\(method) \(path)"] = .response(status: status, body: Data(json.utf8), headers: headers)
    }

    public func fail(_ method: String, _ path: String, with error: URLError) {
        routes["\(method) \(path)"] = .failure(error)
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? ""
        let key = "\(method) \(path)"
        guard let reply = routes[key] else { fatalError("StubTransport: no route for \(key)") }
        switch reply {
        case .failure(let e): throw e
        case .response(let status, let body, let headers):
            let r = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            return (body, r)
        }
    }
}
