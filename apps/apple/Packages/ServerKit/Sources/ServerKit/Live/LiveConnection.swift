import Foundation

public actor LiveConnection {
    public nonisolated let events: AsyncStream<ServerEvent>
    public nonisolated let states: AsyncStream<ConnectionState>
    private let eventsOut: AsyncStream<ServerEvent>.Continuation
    private let statesOut: AsyncStream<ConnectionState>.Continuation

    private let client: ServerClient
    private let transport: any WebSocketTransport
    private let clock: any Clock<Duration>
    private var backoff: Backoff

    private var state: ConnectionState = .idle
    private var runTask: Task<Void, Never>?
    private var runID = 0
    private var channel: (any WebSocketChannel)?
    private var stable = false

    private var nextID = 0
    private var pending: [String: CheckedContinuation<Data?, Error>] = [:]
    private var timers: [String: Task<Void, Never>] = [:]
    private var sending: Set<String> = []
    private var early: [String: Result<Data?, Error>] = [:]

    private enum Outcome: Sendable { case closed(Int?), pingTimeout }

    public init(
        client: ServerClient,
        transport: any WebSocketTransport,
        clock: any Clock<Duration> = ContinuousClock(),
        random: @escaping @Sendable (ClosedRange<Double>) -> Double = { Double.random(in: $0) }
    ) {
        self.client = client
        self.transport = transport
        self.clock = clock
        self.backoff = Backoff(random: random)
        (events, eventsOut) = AsyncStream.makeStream()
        (states, statesOut) = AsyncStream.makeStream()
    }

    public func start() {
        guard runTask == nil else { return }
        runID += 1
        let id = runID
        runTask = Task { await self.run(id) }
    }

    public func stop() async {
        runID += 1
        runTask?.cancel()
        runTask = nil
        let ch = channel
        channel = nil
        failAllPending()
        await ch?.close(code: 1000)
        setState(.idle)
    }

    public func send(_ type: String, _ data: some Encodable & Sendable) async throws -> Data? {
        guard state == .connected, let ch = channel else { throw ServerError.network }
        nextID += 1
        let id = "c\(nextID)"
        let text: String
        do { text = try encodeFrame(type: type, id: id, data: data) } catch { throw ServerError.decoding() }
        sending.insert(id)
        do { try await ch.send(text) } catch {
            sending.remove(id)
            early[id] = nil
            throw ServerError.network
        }
        sending.remove(id)
        if let r = early.removeValue(forKey: id) { return try r.get() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data?, Error>) in
                if Task.isCancelled { cont.resume(throwing: CancellationError()); return }
                pending[id] = cont
                let clock = self.clock
                timers[id] = Task {
                    try? await clock.sleep(for: .seconds(10))
                    if !Task.isCancelled { await self.expire(id) }
                }
            }
        } onCancel: {
            Task { await self.cancelSend(id) }
        }
    }

    // MARK: - Run loop

    private func isCurrent(_ id: Int) -> Bool { id == runID && !Task.isCancelled }

    private func setState(_ s: ConnectionState) {
        guard s != state else { return }
        state = s
        statesOut.yield(s)
    }

    private func finish(_ id: Int, _ s: ConnectionState) {
        guard id == runID else { return }
        runTask = nil
        setState(s)
    }

    private func run(_ id: Int) async {
        while isCurrent(id) {
            guard let endpoint = await client.liveEndpoint() else { return finish(id, .idle) }
            guard isCurrent(id) else { return }
            setState(.connecting)
            var code: Int?
            let generation = endpoint.generation
            do {
                let ch = try await transport.connect(url: endpoint.url, headers: endpoint.headers)
                guard isCurrent(id) else { await ch.close(code: 1000); return }
                channel = ch
                stable = false
                setState(.connected)
                let clock = self.clock
                let stableTimer = Task {
                    try? await clock.sleep(for: .seconds(30))
                    if !Task.isCancelled { await self.markStable(id) }
                }
                let outcome = await runChannel(ch)
                stableTimer.cancel()
                guard isCurrent(id) else { return }
                channel = nil
                failAllPending()
                if case .closed(let c) = outcome { code = c }
            } catch {
                guard isCurrent(id) else { return }
                if let e = error as? ServerError, e.status == 401 {
                    // Revoked only when a second call confirms it (SignOutReason); otherwise signed out, data kept.
                    let reason = await client.handleUpgradeUnauthorized(generation: generation)
                    return finish(id, reason == .revoked ? .revoked : .idle)
                }
            }
            switch code {
            case 4001:
                return finish(id, .replaced)
            case 4003:
                finish(id, .revoked)
                await client.handleRevoked(generation: generation)
                return
            default: break
            }
            if stable { backoff.reset(); stable = false }
            let wait = backoff.next()
            setState(.waiting(seconds: wait))
            do { try await clock.sleep(for: .seconds(wait)) } catch { return }
        }
    }

    private func markStable(_ id: Int) { if id == runID { stable = true } }

    private func runChannel(_ ch: any WebSocketChannel) async -> Outcome {
        let clock = self.clock
        return await withTaskGroup(of: Outcome.self) { group in
            group.addTask {
                while !Task.isCancelled {
                    do { await self.handle(try await ch.receive()) } catch { break }
                }
                return .closed(await ch.closeCode)
            }
            group.addTask {
                while !Task.isCancelled {
                    do { try await clock.sleep(for: .seconds(25)) } catch { break }
                    if !(await Self.pong(ch, clock)) {
                        return Task.isCancelled ? .closed(nil) : .pingTimeout
                    }
                }
                return .closed(nil)
            }
            let first = await group.next() ?? .closed(nil)
            if case .pingTimeout = first { await ch.close(code: 1001) }
            group.cancelAll()
            return first
        }
    }

    /// True when the pong arrived within 10 s.
    private static func pong(_ ch: any WebSocketChannel, _ clock: any Clock<Duration>) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask { (try? await ch.ping()) != nil }
            group.addTask {
                try? await clock.sleep(for: .seconds(10))
                // Close so a ping that ignores cancellation (a real socket) fails now, not at the TCP timeout.
                if !Task.isCancelled { await ch.close(code: 1001) }
                return false
            }
            let ok = await group.next() ?? false
            group.cancelAll()
            return ok
        }
    }

    // MARK: - Incoming frames and pending sends

    private func handle(_ text: String) {
        switch decodeFrame(text) {
        case .event(let e)?: eventsOut.yield(e)
        case .ack(let id, let data)?: resolve(id, .success(data))
        case .error(let id, let code)?: resolve(id, .failure(ServerError(status: 0, code: code)))
        case .malformed?, nil: break  // known-bad and unknown frames are dropped; nothing is logged
        }
    }

    private func resolve(_ id: String, _ result: Result<Data?, Error>) {
        if let c = pending.removeValue(forKey: id) {
            timers.removeValue(forKey: id)?.cancel()
            c.resume(with: result)
        } else if sending.contains(id) {
            early[id] = result
        }
    }

    private func cancelSend(_ id: String) {
        resolve(id, .failure(CancellationError()))
    }

    private func expire(_ id: String) {
        resolve(id, .failure(ServerError(status: 0, code: "timeout")))
    }

    private func failAllPending() {
        for id in Array(pending.keys) { resolve(id, .failure(ServerError.network)) }
        for id in sending { early[id] = .failure(ServerError.network) }
    }
}
