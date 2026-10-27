import Foundation
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
        let store = makeStore()
        let c = client(t, store)
        let counter = Counter()
        await c.setOnUnauthorized { await counter.inc() }
        await withTaskGroup(of: Void.self) { g in
            for _ in 0..<5 {
                g.addTask { let _: Ping? = try? await c.request("GET", "/v1/x", auth: .session) }
            }
        }
        #expect(await counter.n == 1)
        #expect(store.current == nil)
    }

    @Test func baseURLIsHTTPS() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/x", status: 200, json: #"{"ok":true}"#)
        let c = ServerClient(host: "chat.test", session: makeStore(), transport: t)
        let _: Ping = try await c.request("GET", "/v1/x", auth: .none)
        #expect(await t.requests[0].url?.absoluteString == "https://chat.test/v1/x")
    }
}
