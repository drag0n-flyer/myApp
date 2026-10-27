import Foundation
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

private actor Flag { var set = false; func raise() { set = true } }

private final class Rig: @unchecked Sendable {
    let clock = ManualClock()
    let transport = FakeWebSocketTransport()
    let socket = FakeWebSocket()
    let unauthorized = Flag()
    let conn: LiveConnection
    var iterator: AsyncStream<ConnectionState>.Iterator

    init(extra: [FakeWebSocket] = []) async {
        let store = SessionStore(storage: MemorySessionStorage())
        try? store.save(Session(host: "chat.test", accountID: "a", deviceID: "d", token: "tok"))
        let client = ServerClient(baseURL: URL(string: "https://chat.test")!, session: store, transport: StubTransport())
        let flag = unauthorized
        await client.setOnUnauthorized { await flag.raise() }
        await transport.enqueue(socket)
        for s in extra { await transport.enqueue(s) }
        conn = LiveConnection(client: client, transport: transport, clock: clock, random: { $0.upperBound })
        iterator = conn.states.makeAsyncIterator()
    }

    /// Next states, in order.
    func next(_ n: Int) async -> [ConnectionState] {
        var out: [ConnectionState] = []
        for _ in 0..<n { if let s = await iterator.next() { out.append(s) } }
        return out
    }

    func startConnected() async {
        await conn.start()
        _ = await next(2)  // connecting, connected
        _ = await clock.waitForSleepers(2)  // ping timer + stability timer
    }
}

private struct StatusRequest: Encodable, Sendable { var callID: String }

private func inboxFrame() -> String {
    let p = Base64URLData(Data([9, 8, 7])).encoded
    return #"{"type":"inbox","data":{"cursor":1,"kind":"message","conversationID":"cv","epoch":1,"senderAccountID":"a","senderDeviceID":"d","payload":"\#(p)","sentAt":"2026-10-27T10:00:00Z"}}"#
}

private func waitFor(_ cond: @Sendable () async -> Bool) async -> Bool {
    for _ in 0..<2500 {
        if await cond() { return true }
        try? await Task.sleep(nanoseconds: 2_000_000)
    }
    return false
}

@Suite(.serialized) struct LiveConnectionTests {
    @Test func usesWssURLAndBearer() async {
        let rig = await Rig()
        await rig.startConnected()
        let req = await rig.transport.requests.first
        #expect(req?.url.absoluteString == "wss://chat.test/v1/ws")
        #expect(req?.headers["Authorization"] == "Bearer tok")
        await rig.conn.stop()
    }

    @Test func deliversEvents() async {
        let rig = await Rig()
        await rig.startConnected()
        await rig.socket.serverSends(inboxFrame())
        var it = rig.conn.events.makeAsyncIterator()
        guard case .inbox(let item)? = await it.next() else { Issue.record("no event"); return }
        #expect(item.payload == Data([9, 8, 7]))
        await rig.conn.stop()
    }

    @Test func sendWaitsForAck() async throws {
        let rig = await Rig()
        await rig.startConnected()
        let conn = rig.conn, socket = rig.socket
        let t1 = Task { try await conn.send("call.status", StatusRequest(callID: "x")) }
        #expect(await waitFor { await socket.sent.count == 1 })
        #expect(await socket.sent[0] == #"{"data":{"callID":"x"},"id":"c1","type":"call.status"}"#)
        await socket.serverSends(#"{"type":"ack","id":"c1","data":{"state":"ringing"}}"#)
        let data = try #require(try await t1.value)
        #expect(try JSONSerialization.jsonObject(with: data) as? [String: String] == ["state": "ringing"])

        let t2 = Task { try await conn.send("call.status", StatusRequest(callID: "y")) }
        #expect(await waitFor { await socket.sent.count == 2 })
        #expect(await socket.sent[1].contains(#""id":"c2""#))
        await socket.serverSends(#"{"type":"ack","id":"c2"}"#)
        #expect(try await t2.value == nil)
        await conn.stop()
    }

    @Test func sendErrorThrows() async {
        let rig = await Rig()
        await rig.startConnected()
        let conn = rig.conn, socket = rig.socket
        let t = Task { try await conn.send("call.hangup", StatusRequest(callID: "x")) }
        #expect(await waitFor { await socket.sent.count == 1 })
        await socket.serverSends(#"{"type":"error","id":"c1","data":{"code":"not_found"}}"#)
        do { _ = try await t.value; Issue.record("expected error") } catch {
            #expect((error as? ServerError)?.code == "not_found")
        }
        await conn.stop()
    }

    @Test func sendTimesOut() async {
        let rig = await Rig()
        await rig.startConnected()
        let conn = rig.conn, clock = rig.clock
        let t = Task { try await conn.send("call.status", StatusRequest(callID: "x")) }
        #expect(await clock.waitForSleepers(3))  // ping + stability + 10 s timeout
        clock.advance(by: .seconds(10))
        do { _ = try await t.value; Issue.record("expected error") } catch {
            #expect(error as? ServerError == ServerError(status: 0, code: "timeout"))
        }
        await conn.stop()
    }

    @Test func sendWhenNotConnectedIsNetwork() async {
        let rig = await Rig()
        do { _ = try await rig.conn.send("x", StatusRequest(callID: "x")); Issue.record("expected error") } catch {
            #expect(error as? ServerError == ServerError(status: 0, code: "network"))
        }
    }

    @Test func pendingSendFailsWhenChannelDrops() async {
        let rig = await Rig()
        await rig.startConnected()
        let conn = rig.conn, socket = rig.socket
        let t = Task { try await conn.send("call.status", StatusRequest(callID: "x")) }
        #expect(await waitFor { await socket.sent.count == 1 })
        #expect(await rig.clock.waitForSleepers(3))
        await socket.serverCloses(code: 1006)
        do { _ = try await t.value; Issue.record("expected error") } catch {
            #expect((error as? ServerError)?.code == "network")
        }
        await conn.stop()
    }

    @Test func reconnectsWithBackoff() async {
        let rig = await Rig()
        await rig.conn.start()
        #expect(await rig.next(2) == [.connecting, .connected])
        await rig.socket.serverCloses(code: 1006)
        #expect(await rig.next(1) == [.waiting(seconds: 1)])
        #expect(await rig.clock.waitForSleepers(1))
        rig.clock.advance(by: .seconds(1))
        #expect(await rig.next(2) == [.connecting, .connected])
        await rig.conn.stop()
    }

    @Test func backoffGrowsWithoutStableConnection() async {
        let rig = await Rig()
        await rig.conn.start()
        _ = await rig.next(2)
        await rig.socket.serverCloses(code: 1006)
        #expect(await rig.next(1) == [.waiting(seconds: 1)])
        #expect(await rig.clock.waitForSleepers(1))
        rig.clock.advance(by: .seconds(1))
        _ = await rig.next(2)
        let second = (await rig.transport.sockets)[1]
        await second.serverCloses(code: 1006)
        #expect(await rig.next(1) == [.waiting(seconds: 2)])
        await rig.conn.stop()
    }

    @Test func resetsAfterStableConnection() async {
        let rig = await Rig()
        await rig.conn.start()
        _ = await rig.next(2)
        // An unstable drop first, so the next wait would be 2 s without a reset.
        await rig.socket.serverCloses(code: 1006)
        #expect(await rig.next(1) == [.waiting(seconds: 1)])
        #expect(await rig.clock.waitForSleepers(1))
        rig.clock.advance(by: .seconds(1))
        #expect(await rig.next(2) == [.connecting, .connected])
        #expect(await rig.clock.waitForSleepers(2))  // ping + stability timers
        rig.clock.advance(by: .seconds(30))
        _ = await waitFor { await rig.transport.sockets[1].pingCount == 1 }
        #expect(await rig.clock.waitForSleepers(1))  // next ping timer; stability timer fired
        await rig.transport.sockets[1].serverCloses(code: 1006)
        #expect(await rig.next(1) == [.waiting(seconds: 1)])
        await rig.conn.stop()
    }

    @Test func replacedStops() async {
        let rig = await Rig()
        await rig.startConnected()
        await rig.socket.serverCloses(code: 4001)
        #expect(await rig.next(1) == [.replaced])
        #expect(await rig.clock.waitForSleepers(0))
        #expect(await rig.transport.sockets.count == 1)
        #expect(await rig.unauthorized.set == false)
    }

    @Test func revokedStopsReconnecting() async {
        let rig = await Rig()
        await rig.startConnected()
        await rig.socket.serverCloses(code: 4003)
        #expect(await rig.next(1) == [.revoked])
        #expect(await waitFor { await rig.unauthorized.set })
        #expect(await rig.clock.waitForSleepers(0))
        #expect(await rig.transport.sockets.count == 1)
    }

    @Test func pingTimeoutReconnects() async {
        let rig = await Rig()
        await rig.startConnected()
        await rig.socket.setHangPings(true)
        rig.clock.advance(by: .seconds(25))
        #expect(await rig.clock.waitForSleepers(2))  // stability timer + 10 s pong timeout
        rig.clock.advance(by: .seconds(10))
        #expect(await rig.next(1) == [.waiting(seconds: 1)])
        #expect(await rig.socket.clientCloseCode != nil)
        #expect(await rig.clock.waitForSleepers(1))
        rig.clock.advance(by: .seconds(1))
        #expect(await rig.next(2) == [.connecting, .connected])
        await rig.conn.stop()
    }

    @Test func stopClosesCleanly() async {
        let rig = await Rig()
        await rig.startConnected()
        await rig.conn.stop()
        #expect(await rig.next(1) == [.idle])
        #expect(await rig.socket.clientCloseCode == 1000)
        #expect(await rig.transport.sockets.count == 1)
    }
}
