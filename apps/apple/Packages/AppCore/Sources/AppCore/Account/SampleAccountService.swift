import Foundation

@MainActor
public final class SampleAccountService: AccountService {
    public static let sampleEmailCode = "123456"
    public static let sampleInviteCode = "BETA-2026"
    public static let sampleLinkText = "https://home.example.org/link/sample#7Q2K9"

    private let takenNames: Set<String> = ["riya", "admin"]
    public private(set) var lastEmailSentTo: String?
    private var deviceList: [DeviceInfo]

    public let defaultServer = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts,
                                            requiresInvite: true, host: "home.example.org")
    public private(set) var currentServer: ServerPolicy?
    public private(set) var isSignedIn = false
    public var supportsPasskeys: Bool { true }

    public init(platform: DevicePlatform = .iPhone) {
        let now = Date()
        switch platform {
        case .mac:
            deviceList = [
                DeviceInfo(id: "this", name: "This Mac", platform: .mac, lastActive: now, isThisDevice: true),
                DeviceInfo(id: "iphone", name: "iPhone", platform: .iPhone, lastActive: now.addingTimeInterval(-3600), isThisDevice: false),
            ]
        case .iPhone:
            deviceList = [
                DeviceInfo(id: "this", name: "This iPhone", platform: .iPhone, lastActive: now, isThisDevice: true),
                DeviceInfo(id: "mac", name: "MacBook", platform: .mac, lastActive: now.addingTimeInterval(-3600), isThisDevice: false),
            ]
        }
    }

    public func connect(host: String) async throws(AccountError) -> ServerPolicy {
        currentServer = defaultServer
        return defaultServer
    }

    public func checkUsername(_ name: String) async -> UsernameCheck {
        guard UsernameCheck.isValid(name) else { return .invalid }
        return takenNames.contains(name) ? .taken : .available
    }

    public func redeemInvite(code: String) async throws(AccountError) -> Bool {
        guard code == Self.sampleInviteCode else { throw .inviteInvalid }
        return true
    }

    public func startSignUp(username: String, email: String) async throws(AccountError) {
        guard UsernameCheck.isValid(username) else { throw .invalidUsername }
        guard !takenNames.contains(username) else { throw .usernameTaken }
        guard email.contains("@"), email.contains(".") else { throw .invalidEmail }
        lastEmailSentTo = email
    }

    public func verifyEmail(code: String) async throws(AccountError) {
        guard code == Self.sampleEmailCode else { throw .codeInvalid }
    }

    public func finishSignUp() async throws(AccountError) { isSignedIn = true }

    public func signInWithPasskey() async throws(AccountError) { isSignedIn = true }

    public func sendRecoveryCode(to email: String) async throws(AccountError) {
        guard email.contains("@") else { throw .invalidEmail }
        lastEmailSentTo = email
    }

    public func recover(email: String, code: String) async throws(AccountError) {
        guard code == Self.sampleEmailCode else { throw .codeInvalid }
        isSignedIn = true
    }

    public func addPasskey() async throws(AccountError) {}

    public func makeLinkCode() async throws(AccountError) -> LinkCode {
        LinkCode(qrText: Self.sampleLinkText, expiresAt: Date().addingTimeInterval(300))
    }

    public func waitForLinkApproval() async throws(AccountError) { isSignedIn = true }

    public func linkRequest(qrText: String) async throws(AccountError) -> LinkRequest {
        guard let url = URL(string: qrText), url.path.hasPrefix("/link/") else { throw .linkExpired }
        guard url.host == defaultServer.host else { throw .wrongServer }
        return LinkRequest(qrText: qrText, deviceName: "New Mac", platform: .mac)
    }

    public func approveLink(_ request: LinkRequest) async throws(AccountError) {
        deviceList.append(DeviceInfo(id: UUID().uuidString, name: request.deviceName, platform: request.platform,
                                     lastActive: Date(), isThisDevice: false))
    }

    public func devices() async throws(AccountError) -> [DeviceInfo] { deviceList }

    public func removeDevice(id: String) async throws(AccountError) {
        deviceList.removeAll { $0.id == id && !$0.isThisDevice }
    }

    public func deleteAccount() async throws(AccountError) {
        signOutLocally()
    }

    public func signOutLocally() {
        isSignedIn = false
        currentServer = nil
    }
}
