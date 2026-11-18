import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import AppCore

/// Answers from a table by URL and records every request.
private final class FakeTransport: PreviewTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var routes: [String: Result<PreviewResponse, Error>] = [:]
    private var _requests: [String] = []
    var hang = false

    func route(_ url: String, _ response: PreviewResponse) { lock.withLock { routes[url] = .success(response) } }
    func route(_ url: String, fails error: Error) { lock.withLock { routes[url] = .failure(error) } }
    var requests: [String] { lock.withLock { _requests } }

    func get(_ url: URL, accept: String, maxBytes: Int) async throws -> PreviewResponse {
        lock.withLock { _requests.append(url.absoluteString) }
        if hang { try await Task.sleep(for: .seconds(60)) }
        guard let result = lock.withLock({ routes[url.absoluteString] }) else { throw PreviewTransportError.failed }
        return try result.get()
    }
}

private final class FakeResolver: PreviewResolver, @unchecked Sendable {
    private let lock = NSLock()
    private var answers: [String: [[String]]] = [:]
    private var _asked: [String] = []
    private let fallback = ["93.184.216.34"]

    /// The answers for `host`, one per lookup; the last one repeats.
    func answer(_ host: String, _ lookups: [String]...) { lock.withLock { answers[host] = lookups } }
    var asked: [String] { lock.withLock { _asked } }

    func addresses(for host: String) async -> [String] {
        lock.withLock {
            _asked.append(host)
            guard var list = answers[host], !list.isEmpty else { return fallback }
            let next = list.count > 1 ? list.removeFirst() : list[0]
            answers[host] = list
            return next
        }
    }
}

private func html(_ body: String, type: String? = "text/html; charset=utf-8", remote: String? = nil) -> PreviewResponse {
    PreviewResponse(status: 200, contentType: type, body: Data(body.utf8), remoteAddress: remote)
}

private let page = "<title>Hello</title><meta property=\"og:description\" content=\"World\">"
private let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x01, 0x02])

@Suite struct LinkPreviewFetcherTests {
    private func make(_ transport: FakeTransport, _ resolver: FakeResolver = FakeResolver(),
                      shrink: @escaping @Sendable (Data) -> Data? = { _ in nil }, deadline: Duration = .seconds(5)) -> LinkPreviewFetcher {
        LinkPreviewFetcher(transport: transport, resolver: resolver, shrink: shrink, deadline: deadline)
    }

    @Test func aPlainPageGivesACard() async {
        let transport = FakeTransport()
        transport.route("https://example.com/a", html(page))
        let card = await make(transport).preview(for: URL(string: "https://example.com/a")!)
        #expect(card == LinkPreviewCard(url: "https://example.com/a", title: "Hello", desc: "World", image: nil))
        #expect(transport.requests == ["https://example.com/a"])
    }

    @Test(arguments: [
        "http://example.com/a", "https://127.0.0.1/", "https://[::1]/", "https://10.0.0.1/", "https://169.254.169.254/latest/meta-data",
        "https://localhost/", "https://printer.local/", "https://intranet/", "https://user:pass@example.com/", "https://example.com:8443/",
        "ftp://example.com/", "https://2130706433/",
    ])
    func refusedBeforeAnythingIsSent(_ link: String) async {
        let transport = FakeTransport()
        let resolver = FakeResolver()
        transport.route(link, html(page))
        #expect(await make(transport, resolver).preview(for: URL(string: link)!) == nil)
        #expect(transport.requests.isEmpty, "nothing sent to \(link)")
        #expect(resolver.asked.isEmpty, "no DNS lookup for \(link)")
    }

    @Test func aNameThatResolvesToAPrivateAddressIsRefused() async {
        for bad in ["192.168.1.1", "10.0.0.5", "127.0.0.1", "169.254.169.254", "::1", "fd00::1", "100.64.1.1"] {
            let transport = FakeTransport()
            let resolver = FakeResolver()
            resolver.answer("evil.example.com", [bad])
            transport.route("https://evil.example.com/", html(page))
            #expect(await make(transport, resolver).preview(for: URL(string: "https://evil.example.com/")!) == nil, "\(bad)")
            #expect(transport.requests.isEmpty)
        }
    }

    @Test func oneBadAddressAmongGoodOnesRefuses() async {
        let transport = FakeTransport()
        let resolver = FakeResolver()
        resolver.answer("mixed.example.com", ["93.184.216.34", "192.168.0.9"])
        transport.route("https://mixed.example.com/", html(page))
        #expect(await make(transport, resolver).preview(for: URL(string: "https://mixed.example.com/")!) == nil)
        #expect(transport.requests.isEmpty)
    }

    @Test func aSameHostRedirectIsFollowedAndRechecked() async {
        let transport = FakeTransport()
        transport.route("https://example.com/a", PreviewResponse(status: 301, location: "/b"))
        transport.route("https://example.com/b", html(page))
        let resolver = FakeResolver()
        let card = await make(transport, resolver).preview(for: URL(string: "https://example.com/a")!)
        #expect(card?.title == "Hello" && card?.url == "https://example.com/a")
        #expect(resolver.asked == ["example.com", "example.com"], "the address is checked again at every hop")
    }

    @Test func aRedirectToAnotherHostIsRefused() async {
        let transport = FakeTransport()
        transport.route("https://example.com/a", PreviewResponse(status: 302, location: "https://other.example.org/x"))
        transport.route("https://other.example.org/x", html(page))
        #expect(await make(transport).preview(for: URL(string: "https://example.com/a")!) == nil)
        #expect(transport.requests == ["https://example.com/a"])
    }

    @Test func aRedirectToPlainHTTPOrAPrivateAddressIsRefused() async {
        for target in ["http://example.com/b", "https://127.0.0.1/b", "https://example.com:8080/b", "https://user@example.com/b"] {
            let transport = FakeTransport()
            transport.route("https://example.com/a", PreviewResponse(status: 307, location: target))
            transport.route(target, html(page))
            #expect(await make(transport).preview(for: URL(string: "https://example.com/a")!) == nil, "\(target)")
            #expect(transport.requests == ["https://example.com/a"], "\(target)")
        }
    }

    @Test func aRedirectWhoseNameNowResolvesToAPrivateAddressIsRefused() async {
        let transport = FakeTransport()
        let resolver = FakeResolver()
        resolver.answer("example.com", ["93.184.216.34"], ["10.9.9.9"])
        transport.route("https://example.com/a", PreviewResponse(status: 301, location: "/b"))
        transport.route("https://example.com/b", html(page))
        #expect(await make(transport, resolver).preview(for: URL(string: "https://example.com/a")!) == nil)
        #expect(transport.requests == ["https://example.com/a"])
    }

    @Test func atMostThreeRedirects() async {
        let transport = FakeTransport()
        for i in 0..<5 { transport.route("https://example.com/\(i)", PreviewResponse(status: 302, location: "/\(i + 1)")) }
        transport.route("https://example.com/4", html(page))
        #expect(await make(transport).preview(for: URL(string: "https://example.com/0")!) == nil)
        #expect(transport.requests.count == 4)
        let exact = FakeTransport()
        for i in 0..<3 { exact.route("https://example.com/\(i)", PreviewResponse(status: 302, location: "/\(i + 1)")) }
        exact.route("https://example.com/3", html(page))
        #expect(await make(exact).preview(for: URL(string: "https://example.com/0")!)?.title == "Hello")
    }

    @Test func aRedirectWithoutALocationOrAnErrorStatusGivesNothing() async {
        let transport = FakeTransport()
        transport.route("https://example.com/a", PreviewResponse(status: 302))
        transport.route("https://example.com/b", PreviewResponse(status: 404, body: Data(page.utf8)))
        transport.route("https://example.com/c", PreviewResponse(status: 500))
        for path in ["a", "b", "c", "missing"] {
            #expect(await make(transport).preview(for: URL(string: "https://example.com/\(path)")!) == nil, "\(path)")
        }
    }

    @Test func aTenMegabytePageIsRefused() async {
        let transport = FakeTransport()
        transport.route("https://big.example.com/", fails: PreviewTransportError.tooLarge)
        #expect(await make(transport).preview(for: URL(string: "https://big.example.com/")!) == nil)
        // Even if a transport let a long body through, the fetcher does not use it.
        let leaky = FakeTransport()
        leaky.route("https://big.example.com/", PreviewResponse(status: 200, contentType: "text/html",
                                                               body: Data(count: LinkPreviewFetcher.maxBytes + 1)))
        #expect(await make(leaky).preview(for: URL(string: "https://big.example.com/")!) == nil)
        let exact = FakeTransport()
        exact.route("https://big.example.com/", PreviewResponse(status: 200, contentType: "text/html",
                                                               body: Data(page.utf8) + Data(repeating: 0x20, count: LinkPreviewFetcher.maxBytes - page.utf8.count)))
        #expect(await make(exact).preview(for: URL(string: "https://big.example.com/")!)?.title == "Hello")
    }

    @Test func aPageThatNeverAnswersIsGivenUpAt5Seconds() async {
        let transport = FakeTransport()
        transport.hang = true
        let started = ContinuousClock.now
        let card = await make(transport, deadline: .milliseconds(150)).preview(for: URL(string: "https://slow.example.com/")!)
        #expect(card == nil)
        #expect(ContinuousClock.now - started < .seconds(3))
        #expect(LinkPreviewFetcher.totalTime == .seconds(5))
    }

    @Test func notHTMLGivesNothing() async {
        let transport = FakeTransport()
        transport.route("https://example.com/a", html(page, type: "application/pdf"))
        transport.route("https://example.com/b", html(page, type: "image/png"))
        transport.route("https://example.com/c", html("no title at all"))
        for path in ["a", "b", "c"] { #expect(await make(transport).preview(for: URL(string: "https://example.com/\(path)")!) == nil, "\(path)") }
    }

    @Test func theConnectionsOwnAddressIsCheckedAfterTheFact() async {
        let transport = FakeTransport()
        transport.route("https://example.com/a", html(page, remote: "192.168.1.1"))
        transport.route("https://example.com/b", html(page, remote: "93.184.216.34"))
        #expect(await make(transport).preview(for: URL(string: "https://example.com/a")!) == nil)
        #expect(await make(transport).preview(for: URL(string: "https://example.com/b")!)?.title == "Hello")
    }

    @Test func theImageFollowsTheSameRulesAndIsShrunk() async {
        let transport = FakeTransport()
        let withImage = "<title>T</title><meta property=\"og:image\" content=\"https://cdn.example.com/p.png\">"
        transport.route("https://example.com/a", html(withImage))
        transport.route("https://cdn.example.com/p.png", PreviewResponse(status: 200, contentType: "image/png", body: Data(count: 50_000)))
        let card = await make(transport, shrink: { $0.count == 50_000 ? jpeg : nil }).preview(for: URL(string: "https://example.com/a")!)
        #expect(card?.image == jpeg)
        // The shrinker cannot make it fit or fails: the card goes without a picture.
        #expect(await make(transport, shrink: { _ in nil }).preview(for: URL(string: "https://example.com/a")!)?.image == nil)
        #expect(await make(transport, shrink: { _ in Data(repeating: 0xFF, count: 9_000) }).preview(for: URL(string: "https://example.com/a")!)?.image == nil)
        #expect(await make(transport, shrink: { _ in Data([1, 2, 3]) }).preview(for: URL(string: "https://example.com/a")!)?.image == nil, "not a JPEG")
    }

    @Test func anImageOnAPrivateHostOrOverTheCapIsDroppedButTheCardStays() async {
        let resolver = FakeResolver()
        resolver.answer("internal-cdn.example.com", ["10.1.1.1"])
        for source in ["https://internal-cdn.example.com/p.jpg", "https://127.0.0.1/p.jpg", "http://cdn.example.com/p.jpg", "https://cdn.example.com/big.jpg"] {
            let transport = FakeTransport()
            transport.route("https://example.com/a", html("<title>T</title><meta property=\"og:image\" content=\"\(source)\">"))
            transport.route(source, PreviewResponse(status: 200, contentType: "image/jpeg", body: jpeg))
            transport.route("https://cdn.example.com/big.jpg", fails: PreviewTransportError.tooLarge)
            let card = await make(transport, resolver, shrink: { _ in jpeg }).preview(for: URL(string: "https://example.com/a")!)
            #expect(card?.title == "T" && card?.image == nil, "\(source)")
            #expect(!transport.requests.contains(source) || source.hasSuffix("big.jpg"), "\(source) not contacted")
        }
    }

    @Test func anSVGImageIsNotUsed() async {
        let transport = FakeTransport()
        transport.route("https://example.com/a", html("<title>T</title><meta property=\"og:image\" content=\"/p.svg\">"))
        transport.route("https://example.com/p.svg", PreviewResponse(status: 200, contentType: "image/svg+xml", body: Data("<svg/>".utf8)))
        #expect(await make(transport, shrink: { _ in jpeg }).preview(for: URL(string: "https://example.com/a")!)?.image == nil)
    }
}

@Suite struct LinkPreviewLiveSetupTests {
    @Test func theSessionHasNoCookiesCredentialsOrCache() {
        let configuration = URLSessionPreviewTransport.makeConfiguration()
        #expect(configuration.httpCookieStorage == nil)
        #expect(configuration.httpShouldSetCookies == false)
        #expect(configuration.httpCookieAcceptPolicy == .never)
        #expect(configuration.urlCredentialStorage == nil)
        #expect(configuration.urlCache == nil)
        #expect(configuration.timeoutIntervalForRequest <= 5 && configuration.timeoutIntervalForResource <= 5)
        #if canImport(Darwin)
        #expect(configuration.waitsForConnectivity == false)
        #endif
    }

    @Test func theRequestIsAPlainGetWithoutCookies() {
        let request = URLSessionPreviewTransport.request(URL(string: "https://example.com/")!, accept: "text/html")
        #expect(request.httpMethod == "GET" && request.httpShouldHandleCookies == false && request.httpBody == nil)
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil && request.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(request.timeoutInterval <= 5)
    }

    @Test func theSystemResolverAnswersNumbersWithoutTheNetwork() async {
        #expect(await SystemPreviewResolver().addresses(for: "127.0.0.1") == ["127.0.0.1"])
        #expect(await SystemPreviewResolver().addresses(for: "::1") == ["::1"])
        #expect(await SystemPreviewResolver().addresses(for: "definitely-not-a-real-name.invalid").isEmpty)
    }

    @Test func theImageBudgetPicksTheBiggestThatFits() {
        var tried: [[Double]] = []
        let jpegStart = Data([0xFF, 0xD8])
        let data = PreviewImageBudget.fit { side, quality in
            tried.append([Double(side), quality])
            return side <= 96 && quality <= 0.5 ? jpegStart + Data(repeating: 1, count: 8_000) : jpegStart + Data(repeating: 1, count: 20_000)
        }
        #expect(data?.count == 8_002)
        #expect(tried.last == [96, 0.5])
        #expect(PreviewImageBudget.fit { _, _ in Data(repeating: 0xFF, count: 100) } == nil, "not a JPEG")
        #expect(PreviewImageBudget.fit { _, _ in jpegStart + Data(count: 9_000) } == nil, "too big at every setting")
        #expect(PreviewImageBudget.fit { _, _ in nil } == nil)
    }
}

@Suite struct LinkPreviewDraftTests {
    private let link = URL(string: "https://example.com/a")!

    @Test func nothingIsFetchedWhileTheSwitchIsOff() {
        var draft = LinkPreviewDraft()
        #expect(draft.textChanged("see https://example.com/a", enabled: false) == nil)
        #expect(draft.card == nil && draft.loading == nil)
    }

    @Test func aFetchStartsOnceForTheFirstLink() {
        var draft = LinkPreviewDraft()
        #expect(draft.textChanged("see https://example.com/a", enabled: true) == link)
        #expect(draft.textChanged("see https://example.com/a now", enabled: true) == nil, "same link, no second fetch while loading")
        let card = LinkPreviewCard(url: link.absoluteString, title: "T")
        draft.fetched(card, for: link)
        #expect(draft.card == card && draft.loading == nil)
        #expect(draft.textChanged("see https://example.com/a now more", enabled: true) == nil, "same link, no refetch")
        #expect(draft.cardToSend(for: "see https://example.com/a now more") == card)
    }

    @Test func aNonLinkOrAnHTTPLinkFetchesNothing() {
        var draft = LinkPreviewDraft()
        for text in ["hello", "http://example.com/a", "`https://example.com/a`", ""] { #expect(draft.textChanged(text, enabled: true) == nil, "\(text)") }
    }

    @Test func changingTheLinkDropsTheCardAndFetchesTheNewOne() {
        var draft = LinkPreviewDraft()
        draft.textChanged("https://example.com/a", enabled: true)
        draft.fetched(LinkPreviewCard(url: link.absoluteString, title: "A"), for: link)
        let other = URL(string: "https://example.org/b")!
        #expect(draft.textChanged("https://example.org/b", enabled: true) == other)
        #expect(draft.card == nil)
        // A late answer for the old link is ignored.
        draft.fetched(LinkPreviewCard(url: link.absoluteString, title: "A"), for: link)
        #expect(draft.card == nil && draft.loading == other)
        #expect(draft.cardToSend(for: "https://example.org/b") == nil)
    }

    @Test func removingTheLinkFromTheTextClearsTheCard() {
        var draft = LinkPreviewDraft()
        draft.textChanged("https://example.com/a", enabled: true)
        draft.fetched(LinkPreviewCard(url: link.absoluteString, title: "A"), for: link)
        #expect(draft.textChanged("no link now", enabled: true) == nil)
        #expect(draft.card == nil)
    }

    @Test func aRemovedPreviewStaysRemovedForThatDraft() {
        var draft = LinkPreviewDraft()
        draft.textChanged("https://example.com/a", enabled: true)
        draft.fetched(LinkPreviewCard(url: link.absoluteString, title: "A"), for: link)
        draft.remove()
        #expect(draft.card == nil)
        #expect(draft.textChanged("https://example.com/a and more", enabled: true) == nil)
        #expect(draft.textChanged("another https://example.com/a", enabled: true) == nil)
        #expect(draft.card == nil && draft.loading == nil)
        // A different link is a new question.
        #expect(draft.textChanged("https://example.org/b", enabled: true) != nil)
        // The draft ends (sent): the same link may be previewed again in the next one.
        draft.reset()
        #expect(draft.textChanged("https://example.com/a", enabled: true) == link)
    }

    @Test func clearingTheFieldEndsTheDraftToo() {
        var draft = LinkPreviewDraft()
        draft.textChanged("https://example.com/a", enabled: true)
        draft.remove()
        draft.textChanged("", enabled: true)
        #expect(draft.textChanged("https://example.com/a", enabled: true) == link)
    }

    @Test func removingWhileLoadingCancelsTheResult() {
        var draft = LinkPreviewDraft()
        draft.textChanged("https://example.com/a", enabled: true)
        draft.remove()
        draft.fetched(LinkPreviewCard(url: link.absoluteString, title: "A"), for: link)
        #expect(draft.card == nil)
    }

    @Test func theCardGoesOnlyWithTextWhoseFirstLinkIsIts() {
        var draft = LinkPreviewDraft()
        draft.textChanged("https://example.com/a", enabled: true)
        let card = LinkPreviewCard(url: link.absoluteString, title: "A")
        draft.fetched(card, for: link)
        #expect(draft.cardToSend(for: "https://example.com/a") == card)
        #expect(draft.cardToSend(for: "https://example.org/b https://example.com/a") == nil)
        #expect(draft.cardToSend(for: "text only") == nil)
    }

    @Test func switchingOffClearsWhatWasLoaded() {
        var draft = LinkPreviewDraft()
        draft.textChanged("https://example.com/a", enabled: true)
        draft.fetched(LinkPreviewCard(url: link.absoluteString, title: "A"), for: link)
        draft.textChanged("https://example.com/a", enabled: false)
        #expect(draft.card == nil)
    }
}
