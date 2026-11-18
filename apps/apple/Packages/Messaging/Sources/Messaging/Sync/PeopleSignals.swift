import AppCore
import Foundation
import LocalStore

/// Sends one typing or presence signal. `EphemeralOutbox` is the real one; tests use a fake.
@MainActor
public protocol SignalSending {
    func send(chatID: String, body: ContentEnvelope.Body) async
}

/// What is sent when, and what a chat shows, for typing and presence (spec 6.1). The decisions themselves are the
/// pure `TypingSender`, `TypingShown` and `PresenceLine` in AppCore; this keeps the per-chat state, the clock and the
/// switches around them. Never sends to, or shows for, a blocked contact; with a switch off it neither sends nor shows
/// that signal. Signals are never stored, except the time each contact was last witnessed (see `init`).
@MainActor
public final class PeopleSignals {
    /// The most signals waiting to be sent; the oldest go first.
    static let maxQueued = 64

    private let sender: any SignalSending
    private let now: @MainActor () -> Int64
    private let typingEnabled: @MainActor () -> Bool
    private let presenceEnabled: @MainActor () -> Bool
    private let isBlocked: @MainActor (String) -> Bool
    private let presenceTargets: @MainActor () -> [String]
    private let loadSeen: @MainActor (String) -> Int64?
    private let saveSeen: @MainActor (String, Int64) -> Void

    /// Called when something a screen shows may have changed.
    public var onChange: (@MainActor () -> Void)?

    private var composers: [String: TypingSender.State] = [:]
    private var typingOn: [String: Int64] = [:]
    private var lastOnline: [String: Int64] = [:]
    private var witnessed: [String: Int64] = [:]
    private var lastIncoming: [String: String] = [:]
    private var inForeground = false
    private var lastHeartbeatMs: Int64 = 0
    private var shown: [String: Shown] = [:]
    private var queue: [(chatID: String, body: ContentEnvelope.Body)] = []
    private var pump: Task<Void, Never>?

    private struct Shown: Equatable { var typing: Bool; var line: Int }

    /// - Parameters:
    ///   - now: milliseconds since 1970.
    ///   - presenceTargets: the open one-to-one chats `online` and `away` go to.
    ///   - loadSeen/saveSeen: where "last witnessed" is kept per chat. Use `init(repo:...)` for the real store.
    public init(
        sender: any SignalSending, now: @escaping @MainActor () -> Int64,
        typingEnabled: @escaping @MainActor () -> Bool, presenceEnabled: @escaping @MainActor () -> Bool,
        isBlocked: @escaping @MainActor (String) -> Bool, presenceTargets: @escaping @MainActor () -> [String],
        loadSeen: @escaping @MainActor (String) -> Int64?, saveSeen: @escaping @MainActor (String, Int64) -> Void
    ) {
        self.sender = sender
        self.now = now
        self.typingEnabled = typingEnabled
        self.presenceEnabled = presenceEnabled
        self.isBlocked = isBlocked
        self.presenceTargets = presenceTargets
        self.loadSeen = loadSeen
        self.saveSeen = saveSeen
    }

    /// The last witnessed time is kept in the existing key-value store (`kv`, key `presence.seen.<chat id>`): no migration.
    public static func seenKey(_ chatID: String) -> String { "presence.seen.\(chatID)" }

    public convenience init(
        repo: Repository, sender: any SignalSending, now: @escaping @MainActor () -> Int64,
        typingEnabled: @escaping @MainActor () -> Bool, presenceEnabled: @escaping @MainActor () -> Bool,
        isBlocked: @escaping @MainActor (String) -> Bool, presenceTargets: @escaping @MainActor () -> [String]
    ) {
        self.init(
            sender: sender, now: now, typingEnabled: typingEnabled, presenceEnabled: presenceEnabled, isBlocked: isBlocked,
            presenceTargets: presenceTargets,
            loadSeen: { id in
                ((try? repo.get(Self.seenKey(id))) ?? nil).flatMap { String(data: $0, encoding: .utf8) }.flatMap { Int64($0) }
            },
            saveSeen: { id, ms in try? repo.set(Self.seenKey(id), Data(String(ms).utf8)) })
    }

    // MARK: Sending

    /// The composer text changed.
    public func composerChanged(chatID: String, isEmpty: Bool) { composerEvent(chatID, .textChanged(isEmpty: isEmpty)) }

    /// A message was sent, or the composer was left.
    public func composerSent(chatID: String) { composerEvent(chatID, .sent) }

    private func composerEvent(_ chatID: String, _ event: TypingSender.Event) {
        guard typingEnabled(), !isBlocked(chatID) else { return }
        let out = TypingSender.step(composers[chatID] ?? .idle, event, nowMs: now())
        composers[chatID] = out.state.isTyping ? out.state : nil
        if let on = out.send { enqueue(chatID, .typing(on: on)) }
    }

    /// Call about once a second while the app is in the foreground.
    public func tick() {
        let nowMs = now()
        if typingEnabled() {
            for (chatID, state) in composers {
                guard !isBlocked(chatID) else { composers[chatID] = nil; continue }
                let out = TypingSender.step(state, .tick, nowMs: nowMs)
                composers[chatID] = out.state.isTyping ? out.state : nil
                if let on = out.send { enqueue(chatID, .typing(on: on)) }
            }
        } else {
            composers = [:]
        }
        if inForeground, nowMs - lastHeartbeatMs >= Self.heartbeatMs { sendPresence(.online, nowMs: nowMs) }
        refreshShown()
    }

    /// `online` goes out again this often while the app is in the foreground.
    static let heartbeatMs: Int64 = 60_000

    /// The app came to the foreground: `online` now, and again every 60 s (`tick`).
    public func foreground() {
        inForeground = true
        sendPresence(.online, nowMs: now())
    }

    /// The app left the foreground: `away`, and any typing ends.
    public func background() {
        inForeground = false
        for (chatID, _) in composers { composerEvent(chatID, .sent) }
        sendPresence(.away, nowMs: now())
    }

    private func sendPresence(_ state: PresenceState, nowMs: Int64) {
        if state == .online { lastHeartbeatMs = nowMs }
        guard presenceEnabled() else { return }
        for chatID in presenceTargets() where !isBlocked(chatID) { enqueue(chatID, .presence(state)) }
    }

    private func enqueue(_ chatID: String, _ body: ContentEnvelope.Body) {
        queue.append((chatID, body))
        if queue.count > Self.maxQueued { queue.removeFirst(queue.count - Self.maxQueued) }
        guard pump == nil else { return }
        pump = Task { @MainActor [weak self] in
            while let self, !self.queue.isEmpty {
                let next = self.queue.removeFirst()
                // The switches and blocks are read again at the moment of sending.
                guard self.mayStillSend(next.chatID, next.body) else { continue }
                await self.sender.send(chatID: next.chatID, body: next.body)
            }
            self?.pump = nil
        }
    }

    private func mayStillSend(_ chatID: String, _ body: ContentEnvelope.Body) -> Bool {
        guard !isBlocked(chatID) else { return false }
        switch body {
        case .typing: return typingEnabled()
        case .presence: return presenceEnabled()
        default: return false
        }
    }

    /// Waits until everything queued has been handed to the sender (the app does this before it stops the connection).
    public func flush() async { await pump?.value }

    // MARK: Receiving

    /// A signal from `EphemeralChannel.events`.
    public func ingest(_ signal: EphemeralSignal) {
        let chatID = signal.chatID
        guard !isBlocked(chatID) else { return }
        let nowMs = now()
        switch signal.body {
        case .typing(let on):
            guard typingEnabled() else { return }
            if on { typingOn[chatID] = nowMs } else { typingOn[chatID] = nil }
        case .presence(let state):
            guard presenceEnabled() else { return }
            switch state {
            case .online: lastOnline[chatID] = nowMs; witness(chatID, nowMs)
            case .away:
                let after = PresenceLine.afterAway(nowMs: nowMs)
                lastOnline[chatID] = after.lastOnlineMs
                witness(chatID, after.lastWitnessedMs ?? nowMs)
            }
        default: return
        }
        refreshShown()
    }

    /// The newest incoming message of a chat, as seen on every refresh of the chat list. A message that is new since
    /// the last look ends "typing…" and counts as a sign of life now. The first look at a chat only records it, so old
    /// history is not taken for a recent visit.
    public func incomingMessage(chatID: String, id: String) {
        guard let known = lastIncoming[chatID] else { lastIncoming[chatID] = id; return }
        guard known != id else { return }
        lastIncoming[chatID] = id
        typingOn[chatID] = nil
        if !isBlocked(chatID) { witness(chatID, now()) }
        refreshShown()
    }

    private func witness(_ chatID: String, _ ms: Int64) {
        guard ms > (witnessed[chatID] ?? loadSeen(chatID) ?? 0) else { return }
        witnessed[chatID] = ms
        saveSeen(chatID, ms)
    }

    // MARK: Showing

    /// Whether the chat shows "typing…".
    public func isTyping(chatID: String) -> Bool {
        guard typingEnabled(), !isBlocked(chatID) else { return false }
        return TypingShown.isTyping(lastOnMs: typingOn[chatID], nowMs: now())
    }

    /// The presence line under the name in the chat header.
    public func presence(chatID: String) -> PresenceLine.Line {
        guard presenceEnabled(), !isBlocked(chatID) else { return .none }
        return PresenceLine.text(
            lastOnlineMs: lastOnline[chatID], lastWitnessedMs: witnessed[chatID] ?? loadSeen(chatID), nowMs: now())
    }

    /// Tells the screens when the time alone changed what a chat shows.
    private func refreshShown() {
        var next: [String: Shown] = [:]
        for chatID in Set(typingOn.keys).union(lastOnline.keys) {
            let line: Int
            switch presence(chatID: chatID) {
            case .online: line = 1
            case .lastSeen(let ms): line = Int(truncatingIfNeeded: ms) &+ 2
            case .none: line = 0
            }
            next[chatID] = Shown(typing: isTyping(chatID: chatID), line: line)
        }
        guard next != shown else { return }
        shown = next
        onChange?()
    }
}
