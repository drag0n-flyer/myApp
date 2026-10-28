import AppCore
import Foundation
import LocalStore
import Messaging
import SecureMessaging
import ServerKit

/// A value two threads can read and write (the network type and the auto-download settings live on the main
/// actor, but `AttachmentService` reads them from its own actor).
final class SharedValue<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value
    init(_ value: Value) { stored = value }
    var value: Value {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}

/// Builds the real messaging graph for a signed-in device: the shared database and lock in the app-group
/// container, the MLS engine, and the services on top of them. `AppModel` uses it unless `-sampleData`.
@MainActor
struct LiveMessaging {
    let store: LiveChatStore
    let inbox: InboxProcessor
    /// Wipes this account's local data at sign-out (`wipeLocalData`).
    let sync: DeviceSync
    /// Runs own-device reconcile on each live connect and once more 11 minutes later, and handles
    /// `device.revoked` events, one at a time.
    let reconciler: ReconcileScheduler
    let network: SharedValue<NetworkType>
    let autoDownload: SharedValue<AutoDownloadSettings>
    /// The Read receipts switch, read by the outbox and the chat store.
    let readReceipts: SharedValue<Bool>

    /// The app-group container, or nil when the group is not available.
    static var container: URL? {
        MessagingLocations.appGroupID.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
    }

    /// MERGE(I1): deletes this account's MLS signing key from the keychain (the wipe ruling). M5's
    /// `SigningKeyStore` (feat/mls-swift) is wired in here by integration I1. Until then there is no key to delete.
    static let deleteSigningKey: @Sendable () async throws -> Void = {}

    /// Finishes a wipe that was left pending (cut short by a kill or a failure) and is not tied to a running graph.
    /// Throws when a step fails; the marker then stays for the next launch.
    static func finishPendingWipe() async throws {
        guard let container, LocalAccountData.isWipePending(in: container) else { return }
        let database = try Database(url: MessagingLocations.databaseURL(in: container))
        try migrate(database)
        try await DeviceSync.wipe(
            repo: Repository(db: database, now: { Date() }), mediaDirectory: MessagingLocations.mediaURL(in: container),
            lock: ProcessLock(url: MessagingLocations.lockURL(in: container)),
            wipeMLS: { try MessagingLocations.removeMLSStorage(in: container) }, deleteSigningKey: deleteSigningKey)
        try LocalAccountData.clearWipePending(in: container)
    }

    /// Sign-out: stop this graph (no task can write afterwards), then wipe its data. Throws when the wipe fails;
    /// the pending marker (written by the caller before it started) stays, so the next launch retries.
    func wipeAfterSignOut() async throws {
        await reconciler.disconnected()
        await store.shutdown()
        try await sync.wipeLocalData()
        if let container = Self.container { try LocalAccountData.clearWipePending(in: container) }
    }

    /// `nil` when the real MLS engine is not available in this build (see `makeEngine`) or when the shared
    /// container cannot be opened. The caller then keeps the sample store: it never falls back to a fake engine.
    static func make(
        session: Session, client: ServerClient, network: NetworkType, settings: AutoDownloadSettings,
        readReceipts: Bool
    ) async -> LiveMessaging? {
        guard let container,
              makeEngineAvailable(),
              let database = try? Database(url: MessagingLocations.databaseURL(in: container)),
              (try? migrate(database)) != nil else { return nil }
        let now: @Sendable () -> Date = { Date() }
        let lock = ProcessLock(url: MessagingLocations.lockURL(in: container))
        let media = MessagingLocations.mediaURL(in: container)
        // Data left by another account (or by a sign-out wipe that never finished) is wiped before anything opens
        // it, and before the engine opens its storage. If the wipe fails, no live store is installed.
        let repo = Repository(db: database, now: now)
        guard (try? await LocalAccountData.prepare(
            accountID: session.accountID, container: container, repo: repo,
            wipe: { @Sendable in try await DeviceSync.wipe(
                repo: repo, mediaDirectory: media, lock: lock,
                wipeMLS: { try MessagingLocations.removeMLSStorage(in: container) }, deleteSigningKey: deleteSigningKey) }
        )) != nil,
              (try? FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)) != nil,
              let mls = makeEngine(container: container, accountID: session.accountID, deviceID: session.deviceID)
        else { return nil }
        let deps = MessagingDeps(
            me: (session.accountID, session.deviceID), server: client, mls: mls,
            repo: repo, lock: lock,
            escrow: CryptoKitEscrowSealer(), attachments: CryptoKitAttachmentCipher(), notifier: LocalDeviceNotifier(),
            mediaDirectory: media, now: now)

        // One escrow key provider, shared by the outbox and the conversation manager.
        let keys = EscrowKeyProvider(server: client)
        let syncHook = SyncHook()
        let conversations = ConversationManager(deps: deps, escrowKey: keys.closure, syncOnce: { try await syncHook.run() })
        let readReceiptsBox = SharedValue(readReceipts)
        let outbox = Outbox(
            deps: deps, conversations: conversations, keys: keys, readReceiptsEnabled: { readReceiptsBox.value })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: now), controls: outbox)
        syncHook.set { _ = try await inbox.syncOnce() }

        // Sign-out removes the database rows, the media and the MLS storage in the app-group container.
        let sync = DeviceSync(
            deps: deps, conversations: conversations,
            wipeMLS: { try MessagingLocations.removeMLSStorage(in: container) }, deleteSigningKey: deleteSigningKey)

        let networkBox = SharedValue(network)
        let settingsBox = SharedValue(settings)
        let attachments = AttachmentService(
            deps: deps, outbox: outbox, network: { networkBox.value }, settings: { settingsBox.value })
        let store = LiveChatStore(
            deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: attachments)
        let reconciler = ReconcileScheduler(
            deviceID: session.deviceID, reconcile: { try await sync.reconcileOwnDevices() })
        return LiveMessaging(
            store: store, inbox: inbox, sync: sync, reconciler: reconciler,
            network: networkBox, autoDownload: settingsBox, readReceipts: readReceiptsBox)
    }

    /// MERGE(M5): the real engine, `makeMLSEngine(identity:storage:)` (plan 4), keeping its group state in the
    /// shared container so the notification extension can open the same state. Until it exists this returns
    /// nil and the app keeps the sample store. It never returns `FakeMLSEngine`: that has no cryptography.
    static var isAvailable: Bool { makeEngineAvailable() }

    private static func makeEngineAvailable() -> Bool {
        #if canImport(MLSFFIBindings)
        return true
        #else
        return false
        #endif
    }

    private static func makeEngine(container: URL, accountID: String, deviceID: String) -> MLSEngine? {
        #if canImport(MLSFFIBindings)
        return try? makeMLSEngine(identity: "\(accountID)/\(deviceID)", storage: MessagingLocations.mlsURL(in: container))
        #else
        return nil
        #endif
    }
}

/// Lets `ConversationManager` call the inbox processor that is built after it.
private final class SyncHook: @unchecked Sendable {
    private let lock = NSLock()
    private var action: (@Sendable () async throws -> Void)?
    func set(_ action: @escaping @Sendable () async throws -> Void) { lock.withLock { self.action = action } }
    func run() async throws { try await lock.withLock { action }?() }
}
