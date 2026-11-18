import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Finds the largest picture that fits the 8 KiB budget of a preview image. The encoder (ImageIO, in the app) is
/// passed in, so the search itself is plain and tested.
public enum PreviewImageBudget {
    public static let maxBytes = 8_192
    /// A picture declaring more pixels than this is refused before it is decoded.
    public static let maxPixels = 4_000_000

    public static func isWithinPixelLimit(width: Int, height: Int) -> Bool {
        width > 0 && height > 0 && width <= maxPixels && height <= maxPixels && width * height <= maxPixels
    }
    /// Longest side in points, biggest first.
    public static let sides = [160, 128, 96, 72, 56, 40]
    public static let qualities: [Double] = [0.7, 0.5, 0.35, 0.2]

    /// The first encoding (biggest side, then best quality) of at most `maxBytes` that starts like a JPEG; `nil` when none fits.
    public static func fit(encode: (_ side: Int, _ quality: Double) -> Data?) -> Data? {
        for side in sides {
            for quality in qualities {
                if let data = encode(side, quality), data.count <= maxBytes, data.count >= 2,
                   data[data.startIndex] == 0xFF, data[data.startIndex + 1] == 0xD8 { return data }
            }
        }
        return nil
    }
}

/// The system HTTP client set up for a preview.
///
/// Accepted residual risk: URLSession cannot be pinned to the addresses `LinkPreviewFetcher` checked, so a host that
/// changes its DNS answer between the check and the connection (DNS rebinding) can make the system connect to a
/// private address. What then goes out is a blind TCP and TLS handshake to port 443 of that address, and the
/// request. Nothing that comes back is used: when the system reports the connection's address it is checked afterwards
/// and the answer is thrown away. Previews also do not work behind an HTTP proxy: proxy settings are ignored on
/// purpose (a proxy resolves the name itself, so the address check would mean nothing), so such a network gives no preview.
///
/// Set up with: an ephemeral configuration (no cookies, no credentials, no cache, nothing
/// kept on disk), no redirects followed (the fetcher decides), a body cap, a time limit, and HTTPS 1.2 or newer.
public final class URLSessionPreviewTransport: PreviewTransport, @unchecked Sendable {
    public static func makeConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        configuration.httpAdditionalHeaders = [:]
        #if canImport(Darwin)
        configuration.waitsForConnectivity = false
        // No proxy: a proxy would resolve the name itself and the address check would mean nothing.
        configuration.connectionProxyDictionary = [:]
        configuration.tlsMinimumSupportedProtocolVersion = .TLSv12
        #endif
        return configuration
    }

    /// The request as sent: GET, no cookies, a plain browser-like agent that does not name the app.
    static func request(_ url: URL, accept: String) -> URLRequest {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 5)
        request.httpMethod = "GET"
        request.httpShouldHandleCookies = false
        request.setValue(accept, forHTTPHeaderField: "Accept")
        request.setValue("Mozilla/5.0 (compatible)", forHTTPHeaderField: "User-Agent")
        return request
    }

    public init() {}

    public func get(_ url: URL, accept: String, maxBytes: Int) async throws -> PreviewResponse {
        let job = Job(maxBytes: maxBytes)
        let session = URLSession(configuration: Self.makeConfiguration(), delegate: job, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let task = session.dataTask(with: Self.request(url, accept: accept))
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<PreviewResponse, Error>) in
                job.start(task, continuation)
            }
        } onCancel: {
            task.cancel()
        }
    }

    private final class Job: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        private let maxBytes: Int
        private let lock = NSLock()
        private var continuation: CheckedContinuation<PreviewResponse, Error>?
        private var body = Data()
        private var response: HTTPURLResponse?
        private var remote: String?
        private var failure: PreviewTransportError?

        init(maxBytes: Int) { self.maxBytes = maxBytes }

        func start(_ task: URLSessionDataTask, _ continuation: CheckedContinuation<PreviewResponse, Error>) {
            lock.withLock { self.continuation = continuation }
            task.resume()
        }

        private func finish(_ result: Result<PreviewResponse, Error>) {
            let waiting = lock.withLock { () -> CheckedContinuation<PreviewResponse, Error>? in
                defer { continuation = nil }
                return continuation
            }
            waiting?.resume(with: result)
        }

        // Never follow a redirect: the 3xx answer goes back to the fetcher, which checks the next address itself.
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }

        // No credentials of any kind: a login challenge is cancelled; the server certificate is judged by the system.
        func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            #if canImport(Darwin)
            if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
                completionHandler(.performDefaultHandling, nil)
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
            #else
            completionHandler(.performDefaultHandling, nil)   // Linux (tests only): no credential store is set, so none is sent
            #endif
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            let http = response as? HTTPURLResponse
            lock.withLock { self.response = http }
            if response.expectedContentLength > Int64(maxBytes) {
                lock.withLock { failure = .tooLarge }
                completionHandler(.cancel)
            } else {
                completionHandler(.allow)
            }
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            let over = lock.withLock { () -> Bool in
                body.append(data)
                if body.count > maxBytes { failure = .tooLarge; return true }
                return false
            }
            if over { dataTask.cancel() }
        }

        #if canImport(Darwin)
        func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
            let address = metrics.transactionMetrics.last?.remoteAddress
            lock.withLock { remote = address }
        }
        #endif

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            let snapshot = lock.withLock { (response, body, remote, failure) }
            if let failure = snapshot.3 { return finish(.failure(failure)) }
            guard error == nil, let http = snapshot.0 else { return finish(.failure(PreviewTransportError.failed)) }
            finish(.success(PreviewResponse(
                status: http.statusCode, location: http.value(forHTTPHeaderField: "Location"),
                contentType: http.value(forHTTPHeaderField: "Content-Type"), body: snapshot.1, remoteAddress: snapshot.2)))
        }
    }
}

/// DNS through the system (`getaddrinfo`), as address texts. An empty answer when the name does not resolve.
public struct SystemPreviewResolver: PreviewResolver {
    public init() {}

    public func addresses(for host: String) async -> [String] {
        await Task.detached(priority: .utility) { Self.resolve(host) }.value
    }

    static func resolve(_ host: String) -> [String] {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        #if canImport(Darwin)
        hints.ai_socktype = SOCK_STREAM
        #else
        hints.ai_socktype = Int32(SOCK_STREAM.rawValue)
        #endif
        var head: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, "443", &hints, &head) == 0, let first = head else { return [] }
        defer { freeaddrinfo(head) }
        var found: [String] = []
        var cursor: UnsafeMutablePointer<addrinfo>? = first
        while let entry = cursor {
            var buffer = [CChar](repeating: 0, count: 64)
            if let address = entry.pointee.ai_addr,
               getnameinfo(address, entry.pointee.ai_addrlen, &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                let text = String(cString: buffer)
                if !found.contains(text) { found.append(text) }
            }
            cursor = entry.pointee.ai_next
        }
        return found
    }
}
