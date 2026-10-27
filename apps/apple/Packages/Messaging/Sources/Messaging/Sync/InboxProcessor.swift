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
enum InboxError: Error, Equatable {
    /// `mls.process` returned `.joined` for an item that is not a Welcome.
    case joinedWithoutWelcome
}

public actor InboxProcessor {
    static let cursorKey = "inbox.cursor"
    static let pageSize = 100
    static let lockTimeout: Duration = .seconds(5)

    private let deps: MessagingDeps
    private let conversations: ConversationManager
    private let handler: IncomingHandler
    private var membership: Membership { Membership(deps: deps) }
    /// Device IDs whose notice is being shown right now (a second drain must not show it twice).
    private var noticesInFlight: Set<String> = []
    private let controls: ControlSender?
    private enum Section { case fetch, drain }
    private var syncPending = false
    private var streamEnded = false
    private var wake: CheckedContinuation<Void, Never>?
    private var beforeLock: (@Sendable (RawInboxRecord) async -> Void)?
    /// Welcomes that `mls.process` accepted but whose rows are not stored yet (retried without a second `process`).
    private var pendingJoins: [Int64: Incoming] = [:]
    /// Results of `mls.process` that another path (the call sealer) already obtained for a payload that is not its
    /// to handle, by payload hash. `process` uses them instead of calling `mls.process` a second time, which
    /// the engine would reject as a duplicate and leave the stored epoch behind.
    private var adopted: [Data: Incoming] = [:]
    /// Called for every new message from another account, after its rows are stored and outside the lock
    /// (the store starts the auto-download from here).
    private var onStored: (@Sendable (MessageRecord) async -> Void)?
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

    public func setOnStored(_ hook: (@Sendable (MessageRecord) async -> Void)?) { onStored = hook }

    /// Test hook: runs after the cheap pre-checks and before the process lock is taken.
    func setBeforeLockHook(_ hook: (@Sendable (RawInboxRecord) async -> Void)?) { beforeLock = hook }

    /// Hands over an MLS result that was produced outside the inbox pipeline (a commit, Welcome or non-call
    /// message that arrived on the call channel). The item itself still comes from the server inbox; when
    /// it is processed, this result is applied through the normal path (rows, epoch, device lines).
    func adopt(_ incoming: Incoming, forPayloadHash hash: Data) { adopted[hash] = incoming }

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

    /// Runs until the stream ends: syncs on start, then after live items. A live item is only a hint.
    /// Frames can arrive out of cursor order and the stream spans reconnects, so the inbox is always read
    /// from the stored cursor, in order.
    ///
    /// A child task reads the stream and only sets a flag, so events that arrive during a sync are
    /// absorbed: any number of them cost one more sync.
    public func run(events: AsyncStream<ServerEvent>) async {
        syncPending = true  // the sync on start
        streamEnded = false
        let reader = Task {
            for await event in events {
                guard case .inbox = event else { continue }
                await self.signalEvent()
            }
            await self.signalEnd()
        }
        await withTaskCancellationHandler {
            while true {
                if syncPending {
                    syncPending = false
                    _ = try? await syncOnce()
                } else if streamEnded {
                    break
                } else {
                    // No suspension between the checks above and this registration, so no wake-up is lost.
                    await withCheckedContinuation { wake = $0 }
                }
            }
        } onCancel: {
            reader.cancel()
        }
    }

    private func signalEvent() {
        syncPending = true
        wake?.resume()
        wake = nil
    }

    private func signalEnd() {
        streamEnded = true
        wake?.resume()
        wake = nil
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
        var storedFromOthers: [MessageRecord] = []
        scan: while let items = try? deps.repo.rawItems(limit: Self.pageSize), !items.isEmpty {
            for raw in items {
                let (step, applied) = await process(raw)
                if step == .stop { break scan }
                if case .stored(let message)? = applied, message.senderAccount != deps.me.accountID, !message.undecryptable {
                    newMessages[message.chatID, default: []].append(message.id)
                    storedFromOthers.append(message)
                }
            }
        }
        release(.drain)
        if let onStored { for message in storedFromOthers { await onStored(message) } }
        // Messages the notification extension stored: it never sends, so their receipts go out now.
        let pendingReceipts = ((try? deps.repo.entries(prefix: NotificationProcessor.receiptPendingPrefix)) ?? [:])
            .filter { !$0.value.isEmpty }
        var pendingByChat: [String: [String]] = [:]
        for (key, value) in pendingReceipts.sorted(by: { $0.key < $1.key }) {
            let id = String(key.dropFirst(NotificationProcessor.receiptPendingPrefix.count))
            pendingByChat[String(decoding: value, as: UTF8.self), default: []].append(id)
        }
        for (chatID, ids) in pendingByChat { newMessages[chatID, default: []].append(contentsOf: ids) }
        // Network calls (device names, receipts) go after the lock and the gate are released.
        await noticeOwnDevices()
        if let controls {
            for (chatID, ids) in newMessages.sorted(by: { $0.key < $1.key }) {
                // Best effort: a lost delivered receipt only delays the tick.
                do {
                    try await controls.sendControl(.receipt(.delivered, ids: ids), chatID: chatID)
                    // Sent: the extension's markers for this chat are done. A failed send keeps them for the next drain.
                    for id in pendingByChat[chatID] ?? [] {
                        try? deps.repo.set(NotificationProcessor.receiptPendingPrefix + id, Data())
                    }
                } catch {}
            }
        }
    }

    // MARK: New own devices (Design 19)

    static func noticeKey(_ deviceID: String) -> String { "notified.device.\(deviceID)" }

    /// One "New device added" notice per device ID, ever. The apply transaction wrote a pending marker
    /// (`notice.pending.<id>`) per new own device, so every drain posts all pending notices, also ones whose
    /// first attempt was cut short. The name comes from `GET /v1/devices`; when that fails the marker stays and
    /// the next drain tries again. A device the server no longer lists gets "A new device". The pending
    /// marker is cleared after the notice and the `notified.device.<id>` marker, so a kill in between repeats
    /// the notice rather than losing it.
    private func noticeOwnDevices() async {
        let pending = ((try? deps.repo.entries(prefix: IncomingHandler.pendingNoticePrefix)) ?? [:])
            .filter { !$0.value.isEmpty }
            .map { String($0.key.dropFirst(IncomingHandler.pendingNoticePrefix.count)) }.sorted()
        // Claiming happens without a suspension point, so two drains cannot both claim an ID.
        let todo = pending.filter { noticesInFlight.insert($0).inserted }
        guard !todo.isEmpty else { return }
        defer { for id in todo { noticesInFlight.remove(id) } }
        let devices: [Device]
        do { devices = try await deps.server.devices() } catch { return }
        for id in todo {
            if let done = try? deps.repo.get(Self.noticeKey(id)), !done.isEmpty {
                try? deps.repo.set(IncomingHandler.pendingNoticeKey(id), Data())
                continue
            }
            let name = devices.first { $0.id == id }?.name ?? ""
            await deps.notifier.ownDeviceAdded(deviceID: id, name: name.isEmpty ? "A new device" : name)
            try? deps.repo.set(Self.noticeKey(id), Data([1]))
            try? deps.repo.set(IncomingHandler.pendingNoticeKey(id), Data())
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

            await beforeLock?(raw)
            let applied = try await deps.lock.withAsyncLock(timeout: Self.lockTimeout) { () async throws -> AppliedResult? in
                // Another process (the notification extension) may have handled this payload since the check
                // above, so look again under the lock (Design 11).
                if try repo.isProcessed(hash: hash) {
                    pendingJoins[raw.cursor] = nil
                    try repo.deleteRaw(cursor: raw.cursor)
                    return nil
                }
                if try handler.isEchoedOwnCommit(raw, db: repo) {
                    pendingJoins[raw.cursor] = nil
                    try repo.transaction {
                        try repo.markProcessed(hash: hash)
                        try repo.deleteRaw(cursor: raw.cursor)
                    }
                    return nil
                }
                let incoming: Incoming
                if let accepted = pendingJoins[raw.cursor] ?? adopted[hash] {
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
                    guard let serverMode else { throw InboxError.joinedWithoutWelcome }  // never guess the mode (Design 18)
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
                        return try handler.apply(incoming, raw: raw, db: repo, memberKeys: plan.keys)
                    }
                    pendingJoins[raw.cursor] = nil
                    return result
                }
                // The group's members after a commit (under the lock, before the transaction). A failure here only
                // skips the lines for now: the MLS state has advanced, so the item must not be processed again.
                var keys: [MemberKey]?
                if case .commit(let group, _, false) = incoming { keys = try? await membership.keys(of: group) }
                return try repo.transaction {
                    let result = try handler.apply(incoming, raw: raw, db: repo, memberKeys: keys)
                    try repo.markProcessed(hash: hash)
                    try repo.deleteRaw(cursor: raw.cursor)
                    return result
                }
            }
            adopted[hash] = nil
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

extension InboxProcessor: InboxSyncing {}
