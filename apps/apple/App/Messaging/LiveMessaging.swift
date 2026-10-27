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
    let network: SharedValue<NetworkType>
    let autoDownload: SharedValue<AutoDownloadSettings>

    /// `nil` when the real MLS engine is not available in this build (see `makeEngine`) or when the shared
    /// container cannot be opened. The caller then keeps the sample store: it never falls back to a fake engine.
    static func make(
        session: Session, client: ServerClient, network: NetworkType, settings: AutoDownloadSettings
    ) -> LiveMessaging? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: MessagingLocations.appGroupID) else { return nil }
        let media = MessagingLocations.mediaURL(in: container)
        guard (try? FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)) != nil,
              let mls = makeEngine(container: container, accountID: session.accountID, deviceID: session.deviceID),
              let database = try? Database(url: MessagingLocations.databaseURL(in: container)),
              (try? migrate(database)) != nil else { return nil }
        let now: @Sendable () -> Date = { Date() }
        let deps = MessagingDeps(
            me: (session.accountID, session.deviceID), server: client, mls: mls,
            repo: Repository(db: database, now: now), lock: ProcessLock(url: MessagingLocations.lockURL(in: container)),
            escrow: CryptoKitEscrowSealer(), attachments: CryptoKitAttachmentCipher(), notifier: LocalDeviceNotifier(),
            mediaDirectory: media, now: now)

        // One escrow key provider, shared by the outbox and the conversation manager.
        let keys = EscrowKeyProvider(server: client)
        let syncHook = SyncHook()
        let conversations = ConversationManager(deps: deps, escrowKey: keys.closure, syncOnce: { try await syncHook.run() })
        let outbox = Outbox(deps: deps, conversations: conversations, keys: keys)
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: now), controls: outbox)
        syncHook.set { _ = try await inbox.syncOnce() }

        let networkBox = SharedValue(network)
        let settingsBox = SharedValue(settings)
        let attachments = AttachmentService(
            deps: deps, outbox: outbox, network: { networkBox.value }, settings: { settingsBox.value })
        let store = LiveChatStore(
            deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: attachments)
        return LiveMessaging(store: store, inbox: inbox, network: networkBox, autoDownload: settingsBox)
    }

    /// MERGE(M5): the real engine, `makeMLSEngine(identity:storage:)` (plan 4), keeping its group state in the
    /// shared container so the notification extension can open the same state. Until it exists this returns
    /// nil and the app keeps the sample store. It never returns `FakeMLSEngine`: that has no cryptography.
    private static func makeEngine(container: URL, accountID: String, deviceID: String) -> MLSEngine? {
        #if canImport(MLSFFIBindings)
        return try? makeMLSEngine(identity: "\(accountID)/\(deviceID)", storage: container.appendingPathComponent("mls"))
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
