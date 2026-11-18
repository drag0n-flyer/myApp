import Foundation

/// One answer to a `PreviewTransport.get`. Redirects are not followed by the transport: a 3xx answer comes back as it is.
public struct PreviewResponse: Sendable, Equatable {
    public var status: Int
    /// The `Location` header of a redirect.
    public var location: String?
    public var contentType: String?
    public var body: Data
    /// The address the connection really used, when the system reports it (checked again after the fact).
    public var remoteAddress: String?

    public init(status: Int, location: String? = nil, contentType: String? = nil, body: Data = Data(), remoteAddress: String? = nil) {
        self.status = status
        self.location = location
        self.contentType = contentType
        self.body = body
        self.remoteAddress = remoteAddress
    }
}

public enum PreviewTransportError: Error, Equatable, Sendable {
    /// The body is longer than the cap (stopped as soon as the cap was passed, or from the length header).
    case tooLarge
    case failed
}

/// One plain HTTPS GET: no cookies, no credentials, no redirects, a body cap. Live: `URLSessionPreviewTransport`.
public protocol PreviewTransport: Sendable {
    func get(_ url: URL, accept: String, maxBytes: Int) async throws -> PreviewResponse
}

/// DNS, so the answer can be checked before anything is connected to.
public protocol PreviewResolver: Sendable {
    func addresses(for host: String) async -> [String]
}

/// Makes the preview of one link (spec 6.3). Runs only when the sender's app asks (Link previews is on and the text has a
/// link); the receiving side never uses it. Every rule is checked here, in order, before and after each request, and
/// every refusal gives `nil`: the message then goes without a preview.
///
/// What it guarantees: https only; the host name and every address it resolves to are public (`LinkPreviewRule`),
/// before each request, including after each redirect; redirects stay on the same host, at most 3; no user name or
/// password in the address and port 443 only; 512 KiB per body; 5 seconds in all (page and image together); the
/// connection's own address is checked after the fact when the system reports it.
/// What it cannot guarantee: the system resolves the name again when it connects, so a server that answers differently
/// the second time (DNS rebinding) is not stopped before the request is sent, only noticed afterwards.
public struct LinkPreviewFetcher: Sendable {
    public static let maxBytes = 524_288
    public static let maxRedirects = 3
    public static let maxImageBytes = 8_192
    public static let totalTime: Duration = .seconds(5)

    private let transport: PreviewTransport
    private let resolver: PreviewResolver
    private let shrink: @Sendable (Data) -> Data?
    private let deadline: Duration

    /// - Parameters:
    ///   - shrink: re-encodes a downloaded image as a JPEG of at most 8,192 bytes with the system encoder, or `nil` to drop it.
    ///   - deadline: the total time for page and image; 5 seconds unless a test says otherwise.
    public init(transport: PreviewTransport, resolver: PreviewResolver, shrink: @escaping @Sendable (Data) -> Data?,
                deadline: Duration = LinkPreviewFetcher.totalTime) {
        self.transport = transport
        self.resolver = resolver
        self.shrink = shrink
        self.deadline = deadline
    }

    /// The card, or `nil`. Gives up at the deadline without waiting for what is still running: a DNS lookup that the
    /// system will not stop is abandoned, not awaited. Cancelling the caller gives up at once as well.
    public func preview(for url: URL) async -> LinkPreviewCard? {
        let deadline = deadline
        let once = Once()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<LinkPreviewCard?, Never>) in
                let work = Task.detached { [self] in
                    let card = await self.run(url)
                    once.finish(card)
                }
                let timer = Task.detached {
                    try? await Task.sleep(for: deadline)
                    once.finish(nil)
                }
                once.keep([work, timer], continuation)
            }
        } onCancel: {
            once.cancel()
        }
    }

    /// Answers the continuation once; whoever comes second does nothing. Stops the other tasks when it is done.
    private final class Once: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false
        private var resumed = false
        private var cancelled = false
        /// The answer that came before the continuation was handed over.
        private var early: LinkPreviewCard??
        private var continuation: CheckedContinuation<LinkPreviewCard?, Never>?
        private var tasks: [Task<Void, Never>] = []

        /// Called right after the tasks start. If the caller was cancelled before this, everything stops now.
        func keep(_ tasks: [Task<Void, Never>], _ continuation: CheckedContinuation<LinkPreviewCard?, Never>) {
            let answer = lock.withLock { () -> LinkPreviewCard?? in
                self.tasks = tasks
                self.continuation = continuation
                if let early { resumed = true; return early }
                if cancelled { resumed = true; done = true; return .some(nil) }
                return nil
            }
            if let answer {
                tasks.forEach { $0.cancel() }
                continuation.resume(returning: answer)
            }
        }

        func finish(_ card: LinkPreviewCard?) {
            let (waiting, running) = lock.withLock { () -> (CheckedContinuation<LinkPreviewCard?, Never>?, [Task<Void, Never>]) in
                if done { return (nil, []) }
                done = true
                guard let continuation else { early = .some(card); return (nil, tasks) }
                resumed = true
                return (continuation, tasks)
            }
            running.forEach { $0.cancel() }
            waiting?.resume(returning: card)
        }

        func cancel() {
            lock.withLock { cancelled = true }
            finish(nil)
        }
    }

    private func run(_ url: URL) async -> LinkPreviewCard? {
        guard let page = await get(url, accept: "text/html,application/xhtml+xml"), isHTML(page.contentType) else { return nil }
        guard let parsed = LinkPreviewParse.fromHTML(page.body, limit: Self.maxBytes, base: url), let title = parsed.title else { return nil }
        var image: Data?
        if !Task.isCancelled, let imageURL = parsed.imageURL, let picture = await get(imageURL, accept: "image/*"),
           // The bytes decide, not the header: JPEG, PNG, WebP or GIF, with a declared size of at most 4,000,000 pixels.
           let info = ImageHeader.inspect(picture.body), ImageHeader.isWithin(info, maxPixels: PreviewImageBudget.maxPixels),
           let small = shrink(picture.body), small.count <= Self.maxImageBytes, small.count >= 2, small[small.startIndex] == 0xFF, small[small.startIndex + 1] == 0xD8 {
            image = small
        }
        return LinkPreviewCard(url: url.absoluteString, title: title, desc: parsed.desc, image: image)
    }

    private func isHTML(_ contentType: String?) -> Bool {
        guard let type = contentType?.lowercased() else { return false }   // a page with no Content-Type is refused
        return type.hasPrefix("text/html") || type.hasPrefix("application/xhtml+xml")
    }

    /// The final answer (status 2xx) after at most `maxRedirects` same-host redirects, or `nil`.
    private func get(_ start: URL, accept: String) async -> PreviewResponse? {
        var url = start
        for hop in 0...Self.maxRedirects {
            guard !Task.isCancelled, await mayContact(url) else { return nil }
            guard let response = try? await transport.get(url, accept: accept, maxBytes: Self.maxBytes) else { return nil }
            if let remote = response.remoteAddress, LinkPreviewRule.IPAddress(remote)?.isNonPublic != false { return nil }
            if response.body.count > Self.maxBytes { return nil }
            switch response.status {
            case 200..<300:
                return response
            case 301, 302, 303, 307, 308:
                guard hop < Self.maxRedirects, let location = response.location,
                      let next = URL(string: location, relativeTo: url)?.absoluteURL,
                      next.host?.lowercased() == url.host?.lowercased() else { return nil }
                url = next
            default:
                return nil
            }
        }
        return nil
    }

    /// https, port 443, no credentials, and a public host (resolved now).
    private func mayContact(_ url: URL) async -> Bool {
        guard url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.port == nil || url.port == 443 else { return false }
        // The cheap checks that need no DNS first, so a refused name never reaches the resolver.
        guard LinkPreviewRule.allowedHost(host, resolved: ["93.184.216.34"]) else { return false }
        return LinkPreviewRule.allowedHost(host, resolved: await resolver.addresses(for: host))
    }
}
