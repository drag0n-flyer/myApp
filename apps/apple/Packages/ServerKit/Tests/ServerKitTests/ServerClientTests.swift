import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

private struct Ping: Decodable { let ok: Bool }

private func makeStore(signedIn: Bool = true) -> SessionStore {
    let s = SessionStore(storage: MemorySessionStorage())
    if signedIn { try? s.save(Session(host: "chat.test", accountID: "a", deviceID: "d", token: "tok")) }
    return s
}

private func client(_ t: StubTransport, _ store: SessionStore) -> ServerClient {
    ServerClient(baseURL: URL(string: "https://chat.test")!, session: store, transport: t)
}

private actor Counter { var n = 0; func inc() { n += 1 } }
private actor ReasonBox { var reasons: [SignOutReason] = []; func set(_ r: SignOutReason) { reasons.append(r) } }

@Suite struct ServerClientTests {
    @Test func errorEnvelopeMapsToServerError() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 409, json: #"{"error":{"code":"stale_epoch","message":"x","details":{"currentEpoch":3}}}"#)
        do {
            let _: Ping = try await client(t, makeStore()).request("GET", "/v1/x", auth: .session)
            Issue.record("expected error")
        } catch {
            #expect(error == ServerError(status: 409, code: "stale_epoch", retryAfter: nil, details: ["currentEpoch": 3]))
        }
    }

    @Test func retryAfterParsed() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 429, json: #"{"error":{"code":"rate_limited","message":"x"}}"#, headers: ["Retry-After": "120"])
        do {
            let _: Ping = try await client(t, makeStore()).request("GET", "/v1/x", auth: .session)
            Issue.record("expected error")
        } catch {
            #expect(error.retryAfter == 120)
        }
    }

    @Test func transportFailureIsNetwork() async {
        let t = StubTransport()
        await t.fail("GET", "/v1/x", with: URLError(.notConnectedToInternet))
        do {
            let _: Ping = try await client(t, makeStore()).request("GET", "/v1/x", auth: .none)
            Issue.record("expected error")
        } catch {
            #expect(error == ServerError(status: 0, code: "network", retryAfter: nil, details: nil))
        }
    }

    @Test func bearerOnlyWhenAuthed() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 200, json: #"{"ok":true}"#)
        let c = client(t, makeStore())
        let _: Ping = try await c.request("GET", "/v1/x", auth: .session)
        let _: Ping = try await c.request("GET", "/v1/x", auth: .none)
        let reqs = await t.requests
        #expect(reqs[0].value(forHTTPHeaderField: "Authorization") == "Bearer tok")
        #expect(reqs[1].value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func serverClientClearsSessionOnceOn401() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 401, json: #"{"error":{"code":"unauthorized","message":"x"}}"#)
        await t.respond("GET", "/v1/me", status: 401, json: #"{"error":{"code":"unauthorized","message":"x"}}"#)
        let store = makeStore()
        let c = client(t, store)
        let counter = Counter()
        await c.setOnUnauthorized { _ in await counter.inc() }
        await withTaskGroup(of: Void.self) { g in
            for _ in 0..<5 {
                g.addTask { let _: Ping? = try? await c.request("GET", "/v1/x", auth: .session) }
            }
        }
        #expect(await counter.n == 1)
        #expect(store.current == nil)
        // One check that the 401 is real (GET /v1/me), however many calls got it.
        #expect(await t.requests.filter { $0.url?.path == "/v1/me" }.count == 1)
    }

    // Ruling (decision log 2026-10-28): a 401 proves revocation only if it is the server's own error and a second
    // authenticated call (GET /v1/me) agrees. Any other 401 signs out without wiping.
    private static let unauthorized = #"{"error":{"code":"unauthorized","message":"invalid session"}}"#

    private func reasonFor(_ t: StubTransport) async -> (SignOutReason?, Int) {
        let store = makeStore()
        let c = client(t, store)
        let box = ReasonBox()
        await c.setOnUnauthorized { await box.set($0) }
        let _: Ping? = try? await c.request("GET", "/v1/x", auth: .session)
        #expect(store.current == nil)
        return (await box.reasons.first, await t.requests.count)
    }

    @Test func confirmedServer401IsRevoked() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 401, json: Self.unauthorized)
        await t.respond("GET", "/v1/me", status: 401, json: Self.unauthorized)
        let (reason, count) = await reasonFor(t)
        #expect(reason == .revoked)
        #expect(count == 2)
        #expect(await t.requests[1].value(forHTTPHeaderField: "Authorization") == "Bearer tok")
    }

    @Test func unconfirmed401OnlySignsOut() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 401, json: Self.unauthorized)
        await t.respond("GET", "/v1/me", status: 200, json: "{}")
        let (reason, count) = await reasonFor(t)
        #expect(reason == .sessionEnded)
        #expect(count == 2)
    }

    @Test func foreign401OnlySignsOutWithoutACheck() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 401, json: "<html>proxy</html>")
        await t.respond("GET", "/v1/me", status: 401, json: Self.unauthorized)
        let (reason, count) = await reasonFor(t)
        #expect(reason == .sessionEnded)
        #expect(count == 1)
    }

    @Test func confirmNetworkErrorOnlySignsOut() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 401, json: Self.unauthorized)
        await t.fail("GET", "/v1/me", with: URLError(.notConnectedToInternet))
        let (reason, count) = await reasonFor(t)
        #expect(reason == .sessionEnded)
        #expect(count == 2)
    }

    @Test func confirmWithForeignBodyOnlySignsOut() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 401, json: Self.unauthorized)
        await t.respond("GET", "/v1/me", status: 401, json: "<html>proxy</html>")
        #expect(await reasonFor(t).0 == .sessionEnded)
    }

    @Test func baseURLIsHTTPS() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 200, json: #"{"ok":true}"#)
        let c = ServerClient(host: "chat.test", session: makeStore(), transport: t)
        let _: Ping = try await c.request("GET", "/v1/x", auth: .none)
        #expect(await t.requests[0].url?.absoluteString == "https://chat.test/v1/x")
    }

    @Test func reloginRacingInFlight401KeepsNewSession() async {
        let store = makeStore()
        struct Racing: HTTPTransport {
            let store: SessionStore
            func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
                try? store.save(Session(host: "chat.test", accountID: "a", deviceID: "d2", token: "new"))
                let r = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
                return (Data(#"{"error":{"code":"unauthorized","message":"x"}}"#.utf8), r)
            }
        }
        let c = ServerClient(baseURL: URL(string: "https://chat.test")!, session: store, transport: Racing(store: store))
        let counter = Counter()
        await c.setOnUnauthorized { _ in await counter.inc() }
        let _: Ping? = try? await c.request("GET", "/v1/x", auth: .session)
        #expect(await counter.n == 0)
        #expect(store.current?.token == "new")
    }

    @Test func cancellationIsNotNetwork() async {
        let t = StubTransport()
        await t.fail("GET", "/v1/x", with: URLError(.cancelled))
        do {
            let _: Ping = try await client(t, makeStore()).request("GET", "/v1/x", auth: .none)
            Issue.record("expected error")
        } catch {
            #expect(error.code == "cancelled")
        }
    }

    @Test func failedClearKeepsCacheAndDiskConsistent() throws {
        struct Failing: SessionStorage {
            let inner = MemorySessionStorage(Session(host: "h", accountID: "a", deviceID: "d", token: "t"))
            func load() throws -> Session? { try inner.load() }
            func save(_ s: Session) throws { try inner.save(s) }
            func clear() throws { throw URLError(.cannotWriteToFile) }
        }
        let store = SessionStore(storage: Failing())
        #expect(throws: URLError.self) { try store.clear() }
        #expect(store.current?.token == "t")
    }

    @Test func loadFailureIsSurfaced() {
        struct Bad: SessionStorage {
            func load() throws -> Session? { throw URLError(.cannotOpenFile) }
            func save(_ s: Session) throws {}
            func clear() throws {}
        }
        #expect(SessionStore(storage: Bad()).loadFailureType == "URLError")
    }
}
