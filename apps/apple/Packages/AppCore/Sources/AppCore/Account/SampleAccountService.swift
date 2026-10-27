public final class SampleAccountService: AccountService {
    public static let sampleEmailCode = "123456"
    public static let sampleInviteCode = "BETA-2026"

    private let takenNames: Set<String> = ["riya", "admin"]
    public private(set) var lastEmailSentTo: String?

    public let defaultServer = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts,
                                            requiresInvite: true, host: "home.example.org")
    public let linkingCode = "app-link:sample-device-7Q2K9"

    public init() {}

    public func checkUsername(_ name: String) async -> UsernameCheck {
        guard UsernameCheck.isValid(name) else { return .invalid }
        return takenNames.contains(name) ? .taken : .available
    }

    public func sendEmailCode(to email: String) async { lastEmailSentTo = email }

    public func verifyEmail(code: String) async -> Bool { code == Self.sampleEmailCode }

    public func redeemInvite(code: String) async -> Bool { code == Self.sampleInviteCode }
}
