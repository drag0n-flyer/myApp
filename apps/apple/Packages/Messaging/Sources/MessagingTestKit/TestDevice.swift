import Foundation
import LocalStore
import Messaging
import SecureMessagingCore
import ServerKit

public actor RecordingDeviceNotifier: DeviceNotifier {
    public private(set) var added: [(deviceID: String, name: String)] = []
    public init() {}
    public func ownDeviceAdded(deviceID: String, name: String) async {
        added.append((deviceID, name))
    }
}

/// One simulated device: its own fake MLS engine, in-memory database, temp directories and a
/// `ServerClient` that talks to the `FakeServer`.
public struct TestDevice: Sendable {
    public let accountID: String
    public let deviceID: String
    public let deps: MessagingDeps
    public let notifier: RecordingDeviceNotifier
    public let directory: URL

    public init(accountID: String, deviceID: String, deps: MessagingDeps, notifier: RecordingDeviceNotifier, directory: URL) {
        self.accountID = accountID
        self.deviceID = deviceID
        self.deps = deps
        self.notifier = notifier
        self.directory = directory
    }
}

/// The time every test device reports unless the caller passes its own clock.
public let testEpochDate = Date(timeIntervalSince1970: 1_700_000_000)

/// Registers a new device of `account` on the server and builds a fully wired `TestDevice`.
/// `username` is registered for the account on first use.
public func makeDevice(
    _ server: FakeServer, account: String, username: String,
    now: @escaping @Sendable () -> Date = { testEpochDate }
) async -> TestDevice {
    let deviceID = await server.registerDevice(account: account, username: username)
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("messaging-test-\(UUID().uuidString)")
    let media = directory.appendingPathComponent("media")
    try? FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)
    let session = SessionStore(storage: MemorySessionStorage(
        Session(host: FakeServer.host, accountID: account, deviceID: deviceID, token: "test.\(account).\(deviceID)")))
    let client = ServerClient(host: FakeServer.host, session: session,
                              transport: await server.transport(accountID: account, deviceID: deviceID))
    // An in-memory database cannot fail to open; the test kit has nowhere to report it anyway.
    let db = try! Database.inMemory()
    try! migrate(db)
    let notifier = RecordingDeviceNotifier()
    let deps = MessagingDeps(
        me: (account, deviceID), server: client, mls: FakeMLSEngine(identity: "\(account)/\(deviceID)"),
        repo: Repository(db: db, now: now), lock: ProcessLock(url: directory.appendingPathComponent("messaging.lock")),
        escrow: FakeEscrowSealer(), attachments: FakeAttachmentCipher(), notifier: notifier,
        mediaDirectory: media, now: now)
    return TestDevice(accountID: account, deviceID: deviceID, deps: deps, notifier: notifier, directory: directory)
}
