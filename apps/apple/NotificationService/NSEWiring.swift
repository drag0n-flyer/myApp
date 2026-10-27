import AppCore
import Foundation
import LocalStore
import Messaging
import SecureMessaging
import ServerKit

/// Builds `MessagingDeps` from the app group and runs one push through `NotificationProcessor`.
///
/// The database opens here, lazily: `process` is only reached for a push that already passed
/// `NotificationBudget.decodePush`. Any failure to open something returns `.fallback`; the
/// extension never throws and never logs.
enum NSEWiring {
    static func process(pushData: Data, deadline: Date) async -> NotificationOutcome {
        guard let deps = makeDeps() else { return .fallback }
        guard let adapters = NSEAdapters.make(deps: deps) else { return .fallback }
        let processor = NotificationProcessor(
            deps: deps, handler: adapters.handler, inbox: adapters.inbox,
            conversations: adapters.conversations, strings: NotificationStrings.localized())
        return await processor.process(pushData: pushData, deadline: deadline)
    }

    /// nil when there is no session yet (signed out), the container is unavailable, the MLS
    /// engine cannot be made, or the stored database cannot be opened or is newer than this build.
    private static func makeDeps() -> MessagingDeps? {
        let info = Bundle.main.infoDictionary ?? [:]
        let accessGroup = (info["KeychainAccessGroup"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let sessions = SessionStore(storage: KeychainSessionStorage(accessGroup: accessGroup))
        guard let session = sessions.current,
              let container = StoreLocation.appGroup(MessagingLocations.appGroupID)
        else { return nil }
        do {
            let db = try Database(url: MessagingLocations.databaseURL(in: container))
            try NotificationBudget.configure(db)
            try migrate(db)
            let now: @Sendable () -> Date = { Date() }
            guard let mls = NSEAdapters.makeEngine(container: container, accountID: session.accountID, deviceID: session.deviceID)
            else { return nil }
            return MessagingDeps(
                me: (session.accountID, session.deviceID),
                server: ServerClient(host: session.host, session: sessions),
                mls: mls,
                repo: Repository(db: db, now: now),
                lock: ProcessLock(url: MessagingLocations.lockURL(in: container)),
                escrow: NSEUnavailable(), attachments: NSEUnavailable(), notifier: NSEUnavailable(),
                mediaDirectory: MessagingLocations.mediaURL(in: container),
                now: now)
        } catch {
            return nil
        }
    }
}

/// The extension never sends, seals or downloads: these ports exist only to fill `MessagingDeps`.
private struct NSEUnavailable: EscrowSealer, AttachmentCipher, DeviceNotifier {
    func seal(_ plaintext: Data, to recipientKey: Data) throws -> Data { throw EscrowError.unavailable }
    func encrypt(file: URL, to: URL) throws -> (key: Data, digest: Data, size: Int64, cipherSize: Int64) {
        throw EscrowError.unavailable
    }
    func decrypt(file: URL, to: URL, key: Data, digest: Data) throws { throw EscrowError.unavailable }
    /// The notification processor shows "New device added" itself (Design 19).
    func ownDeviceAdded(deviceID: String, name: String) async {}
}
