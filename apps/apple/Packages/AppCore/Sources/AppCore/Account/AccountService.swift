import Foundation

public enum UsernameCheck: Equatable, Sendable {
    case available, taken, invalid, unknown

    /// 3–32 characters: lowercase letters, digits, or underscore, starting with a letter.
    public static func isValid(_ name: String) -> Bool {
        guard (3...32).contains(name.count), let first = name.first, first.isASCII, first.isLowercase else { return false }
        return name.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber || $0 == "_") }
    }
}

public enum AccountError: Error, Equatable, Sendable {
    case inviteRequired, inviteInvalid, usernameTaken, invalidUsername, invalidEmail
    case codeInvalid, codeExpired, tooManyAttempts(retryAfterSeconds: Int?)
    case linkExpired, wrongServer, passkeyUnavailable, cancelled, network, server
    /// The approving device could not confirm with Face ID, Touch ID or the passcode.
    case biometricsUnavailable
}

/// Shown as a QR code on a device waiting to be linked.
public struct LinkCode: Equatable, Sendable {
    public let qrText: String
    public let expiresAt: Date

    public init(qrText: String, expiresAt: Date) {
        self.qrText = qrText
        self.expiresAt = expiresAt
    }
}

/// What the approving iPhone learns from a scanned code, shown before it approves.
public struct LinkRequest: Equatable, Sendable {
    public let qrText: String
    public let deviceName: String
    public let platform: DevicePlatform
    /// When the new device asked to be linked, as reported by the server.
    public let requestedAt: Date?

    public init(qrText: String, deviceName: String, platform: DevicePlatform, requestedAt: Date? = nil) {
        self.qrText = qrText
        self.deviceName = deviceName
        self.platform = platform
        self.requestedAt = requestedAt
    }
}

/// How linking ended on the new device. The account is signed in either way.
public struct LinkedResult: Equatable, Sendable {
    /// False when no contact list arrived (none was sent, or it could not be opened); the app says
    /// "Contacts weren't copied".
    public let contactsCopied: Bool

    public init(contactsCopied: Bool) { self.contactsCopied = contactsCopied }
}

/// How approving ended on the approving device.
public struct LinkApproval: Equatable, Sendable {
    /// False when the contact list was too large to send: the new device was approved without it, and
    /// the app says the new device starts without its contacts.
    public let snapshotSent: Bool

    public init(snapshotSent: Bool) { self.snapshotSent = snapshotSent }
}

public struct DeviceInfo: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let platform: DevicePlatform
    public let lastActive: Date
    public let isThisDevice: Bool

    public init(id: String, name: String, platform: DevicePlatform, lastActive: Date, isThisDevice: Bool) {
        self.id = id
        self.name = name
        self.platform = platform
        self.lastActive = lastActive
        self.isThisDevice = isThisDevice
    }
}

/// Account sign-up and linking. `SampleAccountService` now; the Go server later.
@MainActor
public protocol AccountService: AnyObject {
    var defaultServer: ServerPolicy { get }
    var currentServer: ServerPolicy? { get }
    var isSignedIn: Bool { get }
    /// For `currentServer`, else `defaultServer`.
    var supportsPasskeys: Bool { get }

    func connect(host: String) async throws(AccountError) -> ServerPolicy
    func checkUsername(_ name: String) async -> UsernameCheck
    func redeemInvite(code: String) async throws(AccountError) -> Bool
    func startSignUp(username: String, email: String) async throws(AccountError)
    func verifyEmail(code: String) async throws(AccountError)
    /// Creates the passkey when supported.
    func finishSignUp() async throws(AccountError)
    func signInWithPasskey() async throws(AccountError)
    func sendRecoveryCode(to email: String) async throws(AccountError)
    func recover(email: String, code: String) async throws(AccountError)
    func addPasskey() async throws(AccountError)
    func makeLinkCode() async throws(AccountError) -> LinkCode
    /// Returns when this device is signed in. A contact list that can't be opened is skipped, not an error.
    func waitForLinkApproval() async throws(AccountError) -> LinkedResult
    /// Approving side, step 1: read a scanned code. Throws `.wrongServer` or `.linkExpired`.
    func linkRequest(qrText: String) async throws(AccountError) -> LinkRequest
    /// Approving side, step 2: confirm with biometrics, then approve. Throws `.biometricsUnavailable`.
    /// A contact list too large to send is left out; the result says so.
    func approveLink(_ request: LinkRequest) async throws(AccountError) -> LinkApproval
    func devices() async throws(AccountError) -> [DeviceInfo]
    func removeDevice(id: String) async throws(AccountError)
    func deleteAccount() async throws(AccountError)
    func signOutLocally()
}
