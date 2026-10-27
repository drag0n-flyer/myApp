import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit

/// Raw-first inbox pipeline (Design 11, 12, 17).
///
/// Fetched items are stored in `inbox_raw` and acknowledged only after that write. Each raw item is then
/// processed in cursor order: the MLS result and its rows are written in one transaction that also marks
/// the payload hash processed and deletes the raw row. The process lock is held only around that step,
/// never across a network call.
public actor InboxProcessor {
    static let cursorKey = "inbox.cursor"
    static let pageSize = 100
    static let lockTimeout: Duration = .seconds(5)

    private let deps: MessagingDeps
    private let conversations: ConversationManager
    private let handler: IncomingHandler
    private let controls: ControlSender?
    private enum Section { case fetch, drain }
    private var syncRunning = false
    private var syncAgain = false
    /// Welcomes that `mls.process` accepted but whose rows are not stored yet (retried without a second `process`).
    private var pendingJoins: [Int64: Incoming] = [:]
    private var held: Set<Section> = []
    private var waiters: [Section: [CheckedContinuation<Void, Never>]] = [:]

    /// - Parameter controls: sends one `delivered` receipt per conversation after each drain (lane B's `Outbox`).
    public init(
        deps: MessagingDeps, conversations: ConversationManager, handler: IncomingHandler, controls: ControlSender? = nil
    ) {
        self.deps = deps
        self.conversations = conversations
        self.handler = handler
        self.controls = controls
    }

    // MARK: Sync

    /// Fetches the inbox from the stored cursor, stores and acknowledges it, then drains the raw rows.
    /// Returns the number of items fetched. `maxItems` stops fetching after that many (rounded up to a page).
    @discardableResult
    public func syncOnce(maxItems: Int = .max) async throws -> Int {
        await acquire(.fetch)
        var fetched = 0
        var failure: Error?
        do {
            fetched = try await fetch(maxItems: maxItems)
        } catch {
            failure = error
        }
        release(.fetch)
        // Items stored before a failure are still processed.
        await drainRaw()
        if let failure { throw failure }
        return fetched
    }

    private func fetch(maxItems: Int) async throws -> Int {
        var fetched = 0
        while fetched < maxItems {
            let after = try storedCursor()
            let page = try await deps.server.inbox(after: after, limit: Self.pageSize)
            guard let last = page.items.map(\.cursor).max() else { break }
            try deps.repo.insertRaw(page.items.map(Self.raw))
            try await deps.server.ackInbox(upTo: last)
            try deps.repo.set(Self.cursorKey, Data(String(last).utf8))
            fetched += page.items.count
            if !page.more { break }
        }
        return fetched
    }

    /// Runs until the stream ends: syncs on start, then on every live item. A live item is only a hint.
    /// Frames can arrive out of cursor order and the stream spans reconnects, so the inbox is always read
    /// from the stored cursor, in order. A burst of events costs at most one extra sync.
    public func run(events: AsyncStream<ServerEvent>) async {
        await requestSync()
        for await event in events {
            guard case .inbox = event else { continue }
            await requestSync()
        }
    }

    /// Runs `syncOnce`, or when one is already running marks "sync again" so it runs once more after it.
    private func requestSync() async {
        if syncRunning {
            syncAgain = true
            return
        }
        syncRunning = true
        repeat {
            syncAgain = false
            _ = try? await syncOnce()
        } while syncAgain
        syncRunning = false
    }

    private func storedCursor() throws -> Int64? {
        guard let data = try deps.repo.get(Self.cursorKey), let text = String(data: data, encoding: .utf8) else { return nil }
        return Int64(text)
    }

    private static func raw(_ item: InboxItem) -> RawInboxRecord {
        RawInboxRecord(
            cursor: item.cursor, kind: item.kind, conversationID: item.conversationID, seq: item.seq, epoch: item.epoch,
            senderAccount: item.senderAccountID, senderDevice: item.senderDeviceID, payload: item.payload, sentAt: item.sentAt)
    }

    // MARK: Drain

    private enum Step {
        case done
        /// Not processed and not dropped: a lock timeout, a busy engine, a failed database write or a network
        /// error. The raw row stays and the next drain tries again.
        case stop
    }

    /// Processes the raw rows in cursor order. Stops at the first row that cannot be handled for a
    /// temporary reason, so no later row is processed before it.
    public func drainRaw() async {
        await acquire(.drain)
        var newMessages: [String: [String]] = [:]
        scan: while let items = try? deps.repo.rawItems(limit: Self.pageSize), !items.isEmpty {
            for raw in items {
                let (step, applied) = await process(raw)
                if step == .stop { break scan }
                if case .stored(let message)? = applied, message.senderAccount != deps.me.accountID, !message.undecryptable {
                    newMessages[message.chatID, default: []].append(message.id)
                }
            }
        }
        release(.drain)
        // Receipts are network calls, so they go after the lock and the gate are released.
        if let controls {
            for (chatID, ids) in newMessages.sorted(by: { $0.key < $1.key }) {
                await controls.sendControl(.receipt(.delivered, ids: ids), chatID: chatID)
            }
        }
    }

    private func process(_ raw: RawInboxRecord) async -> (Step, AppliedResult?) {
        let repo = deps.repo
        do {
            let hash = PortableSHA256.hash(raw.payload)
            if try repo.isProcessed(hash: hash) {
                try repo.deleteRaw(cursor: raw.cursor)
                return (.done, nil)
            }
            if try handler.isEchoedOwnCommit(raw, db: repo) {
                try repo.transaction {
                    try repo.markProcessed(hash: hash)
                    try repo.deleteRaw(cursor: raw.cursor)
                }
                return (.done, nil)
            }

            // A Welcome needs the server's mode. Fetch it before the lock; a Welcome that is not processed
            // yet can be retried, one that is processed cannot.
            var serverMode: String?
            if raw.kind == "welcome" {
                do {
                    serverMode = try await deps.server.conversation(id: raw.conversationID).mode
                } catch {
                    if (400..<500).contains(error.status), error.status != 408, error.status != 429 {
                        // The server will never answer for this conversation: treat the Welcome as lost.
                        try repo.transaction {
                            try handler.storePlaceholder(for: raw, db: repo)
                            try repo.markProcessed(hash: hash)
                            try repo.deleteRaw(cursor: raw.cursor)
                        }
                        return (.done, nil)
                    }
                    return (.stop, nil)
                }
            }

            let applied = try await deps.lock.withAsyncLock(timeout: Self.lockTimeout) { () async throws -> AppliedResult? in
                // Another process (the notification extension) may have handled this payload since the check
                // above, so look again under the lock (Design 11).
                if try repo.isProcessed(hash: hash) {
                    try repo.deleteRaw(cursor: raw.cursor)
                    return nil
                }
                if try handler.isEchoedOwnCommit(raw, db: repo) {
                    try repo.transaction {
                        try repo.markProcessed(hash: hash)
                        try repo.deleteRaw(cursor: raw.cursor)
                    }
                    return nil
                }
                let incoming: Incoming
                if let accepted = pendingJoins[raw.cursor] {
                    incoming = accepted
                } else {
                    do {
                        incoming = try await deps.mls.process(raw.payload)
                    } catch MLSError.busy {
                        throw MLSError.busy
                    } catch MLSError.storage(let reason) {
                        throw MLSError.storage(reason)  // possibly temporary (disk): retry later
                    } catch {
                        // Anything else the engine rejects is permanent for this payload.
                        try repo.transaction {
                            try handler.storePlaceholder(for: raw, db: repo)
                            try repo.markProcessed(hash: hash)
                            try repo.deleteRaw(cursor: raw.cursor)
                        }
                        return nil
                    }
                }
                if case .joined(let group, _) = incoming {
                    // The Welcome counts as processed only together with its conversation row.
                    guard let serverMode else { throw SyncError.noPeer }  // never guess the mode (Design 18)
                    pendingJoins[raw.cursor] = incoming
                    let plan: ConversationManager.JoinPlan
                    do {
                        plan = try await conversations.planJoin(
                            group: group, serverConversationID: raw.conversationID, serverMode: serverMode)
                    } catch SyncError.noPeer {
                        // Never going to work for this Welcome: drop it, store nothing.
                        pendingJoins[raw.cursor] = nil
                        try repo.transaction {
                            try repo.markProcessed(hash: hash)
                            try repo.deleteRaw(cursor: raw.cursor)
                        }
                        return nil
                    }
                    let result = try repo.transaction {
                        try plan.store(in: repo)
                        try repo.markProcessed(hash: hash)
                        try repo.deleteRaw(cursor: raw.cursor)
                        return try handler.apply(incoming, raw: raw, db: repo)
                    }
                    pendingJoins[raw.cursor] = nil
                    return result
                }
                return try repo.transaction {
                    let result = try handler.apply(incoming, raw: raw, db: repo)
                    try repo.markProcessed(hash: hash)
                    try repo.deleteRaw(cursor: raw.cursor)
                    return result
                }
            }
            return (.done, applied)
        } catch {
            return (.stop, nil)
        }
    }

    // MARK: Mutual exclusion

    /// A first-in-first-out async mutex per section. Actors are reentrant, so a second `syncOnce` or
    /// `drainRaw` could otherwise interleave at an `await` and process a row twice.
    private func acquire(_ section: Section) async {
        if !held.contains(section) {
            held.insert(section)
            return
        }
        await withCheckedContinuation { waiters[section, default: []].append($0) }
    }

    private func release(_ section: Section) {
        if var queue = waiters[section], !queue.isEmpty {
            let next = queue.removeFirst()
            waiters[section] = queue
            next.resume()  // the section passes straight to the next waiter
        } else {
            held.remove(section)
        }
    }
}
