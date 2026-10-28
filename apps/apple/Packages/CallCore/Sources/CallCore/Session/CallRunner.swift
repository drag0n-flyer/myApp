import Foundation
import AppCore

/// Runs one call: owns its `CallSessionMachine`, executes the machine's effects in order on a serial task chain,
/// and feeds back media events, opened server payloads, stats (every 2 s) and ticks (every 0.5 s).
/// No platform code: media comes from the `makeMedia` factory (WebRTC arrives with lane M), signaling and sealing
/// are injected, so the whole flow runs on Linux against the fakes. `LiveCallEngine` creates one per call.
@MainActor
public final class CallRunner {
    public let callID: String
    public let contactID: String

    private var machine: CallSessionMachine
    private let signaling: CallSignaling
    private let sealer: CallSignalSealer
    private let clock: Clock
    private let makeMedia: @MainActor () -> MediaSession
    private let publish: @MainActor (CallState?) -> Void
    private let record: @MainActor (CallSummary, String, Bool, Date) -> Void
    private let onStats: @MainActor (MediaStats) -> Void
    private let log: @Sendable (String) -> Void
    private let tickInterval: Duration
    private let statsEveryTicks: Int

    private var media: MediaSession?
    private var tail: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var mediaEvents: Task<Void, Never>?
    private var generation = 0

    public init(context: CallContext, callID: String, contactID: String,
                signaling: CallSignaling, sealer: CallSignalSealer, clock: Clock,
                makeMedia: @escaping @MainActor () -> MediaSession,
                tickInterval: Duration = .milliseconds(500), statsEveryTicks: Int = 4,
                publish: @escaping @MainActor (CallState?) -> Void,
                record: @escaping @MainActor (CallSummary, String, Bool, Date) -> Void,
                onStats: @escaping @MainActor (MediaStats) -> Void = { _ in },
                log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.machine = CallSessionMachine(context: context, clock: clock)
        self.callID = callID; self.contactID = contactID
        self.signaling = signaling; self.sealer = sealer; self.clock = clock
        self.makeMedia = makeMedia; self.publish = publish; self.record = record
        self.onStats = onStats; self.log = log
        self.tickInterval = tickInterval; self.statsEveryTicks = max(1, statsEveryTicks)
    }

    public var state: CallState? { machine.state }
    public var isFinished: Bool { machine.state?.isEnded ?? false }

    // MARK: - Inputs

    /// Feeds the machine and runs what it asks for. State changes and history go out at once; I/O is queued in order.
    public func handle(_ input: CallInput) {
        let effects = machine.handle(input)
        var queued: [CallEffect] = []
        var publishState = false
        for effect in effects {
            switch effect {
            case .publish: publishState = true
            case .recordHistory(let summary, let outgoing): record(summary, contactID, outgoing, clock.now)
            default: queued.append(effect)
            }
        }
        if publishState { publish(machine.state) }
        if !queued.isEmpty { enqueue(queued) }
        if machine.state != nil, !isFinished { startTicker() }
    }

    /// A server event for this call. Payloads are opened (by the contact's account) before the machine sees them,
    /// in arrival order. Rings are the engine's business and are ignored here.
    public func ingest(_ event: CallServerEvent) async {
        switch event {
        case .ring: return
        case .ended(let id, _):
            guard sameCall(id) else { return }
            handle(.server(event))
        case .answered(let id, let device, let payload):
            guard sameCall(id) else { return }
            handle(.server(event))
            await open(payload, from: device)
        case .signal(let id, let device, let payload):
            guard sameCall(id) else { return }
            await open(payload, from: device)
        }
    }

    public func pathChanged() { handle(.pathChanged) }
    public func update(context: CallContext) { handle(.contextChanged(context)) }

    /// Waits until every queued effect (and the inputs they cause) has run.
    public func settled() async {
        while let t = tail {
            await t.value
            if tail == t { break }
        }
    }

    /// Sign-out or wipe: hangs up a live call (the hangup goes out while signaling is still up), closes the media,
    /// and returns once every effect has run. Call it before the connection is stopped.
    public func shutdown() async {
        if !isFinished { handle(.hangup) }
        await settled()
    }

    private func sameCall(_ id: String) -> Bool { id == callID }

    private func open(_ sealed: Data, from device: String) async {
        // The credential device goes with the payload; the machine drops it when it differs from `device`.
        handle(await sealer.openInput(sealed, from: contactID, fromDeviceID: device))
    }

    // MARK: - Effects

    private func enqueue(_ effects: [CallEffect]) {
        let previous = tail
        let gen = generation
        tail = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            await self.run(effects, generation: gen)
        }
    }

    private func run(_ effects: [CallEffect], generation gen: Int) async {
        for effect in effects {
            guard gen == generation else { return }
            do {
                try await perform(effect)
            } catch {
                log("call effect failed: \(type(of: error))")
                if isCritical(effect) {
                    // The rest of this batch no longer makes sense (no invite without media, no answer without an SDP).
                    handle(.localFailure)
                    return
                }
            }
        }
    }

    private func isCritical(_ e: CallEffect) -> Bool {
        switch e {
        case .configureMedia, .makeOffer, .acceptOffer, .acceptAnswer, .sendInvite, .sendAnswer: true
        default: false
        }
    }

    private func perform(_ effect: CallEffect) async throws {
        switch effect {
        case .publish, .recordHistory: break   // handled in `handle`
        case .configureMedia(let policy, let video):
            let servers = try await signaling.iceServers()
            let m = makeMedia()
            media = m
            mediaEvents?.cancel()
            mediaEvents = Task { [weak self] in
                for await e in m.events { self?.handle(.media(e)) }
            }
            try await m.configure(iceServers: servers, policy: policy, video: video)
        case .sendInvite(let p):
            try await signaling.invite(callID: callID, to: contactID, video: p.video ?? false,
                                       payload: try await sealer.seal(p, to: contactID))
        case .sendAnswer(let p): try await signaling.answer(callID: callID, payload: try await sealer.seal(p, to: contactID))
        case .sendSignal(let p): try await signaling.signal(callID: callID, payload: try await sealer.seal(p, to: contactID))
        case .sendDecline: try await signaling.decline(callID: callID)
        case .sendHangup: try await signaling.hangup(callID: callID)
        case .makeOffer(let receive, let restart):
            guard let m = media else { throw RunnerError.noMedia }
            handle(.localOffer(try await m.makeOffer(receive: receive, iceRestart: restart)))
        case .acceptOffer(let sdp, let send, let receive):
            guard let m = media else { throw RunnerError.noMedia }
            handle(.localAnswer(try await m.acceptOffer(sdp, send: send, receive: receive)))
        case .acceptAnswer(let sdp, let send):
            guard let m = media else { throw RunnerError.noMedia }
            try await m.acceptAnswer(sdp, send: send)
        case .rollback: try await media?.rollback()
        case .addCandidate(let c): try await media?.add(c)
        case .setAudioMaxBitrate(let bps): await media?.setAudioMaxBitrate(bps)
        case .setVideo(let tier, let ceiling): await media?.setVideo(tier, ceiling: ceiling)
        case .setMuted(let on): await media?.setMuted(on)
        case .setNoiseSuppression(let mode): await media?.setNoiseSuppression(mode)
        case .closeMedia:
            ticker?.cancel(); ticker = nil
            mediaEvents?.cancel(); mediaEvents = nil
            let m = media
            media = nil
            await m?.close()
        }
    }

    enum RunnerError: Error { case noMedia }

    // MARK: - Timers

    private func startTicker() {
        guard ticker == nil else { return }
        let every = tickInterval, statsEvery = statsEveryTicks
        ticker = Task { [weak self] in
            var count = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: every)
                guard !Task.isCancelled, let self, !self.isFinished else { return }
                self.handle(.tick)
                count += 1
                if count % statsEvery == 0, let m = self.media {
                    let s = await m.stats()
                    guard !Task.isCancelled, !self.isFinished else { return }
                    self.log("audio codec \(s.audioCodec ?? "unknown"), available \(Int(s.availableOutgoingBitrate ?? 0)) bps")
                    self.onStats(s)
                    self.handle(.stats(s))
                }
            }
        }
    }
}
