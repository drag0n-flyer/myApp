public enum UsernameCheck: Equatable, Sendable {
    case available, taken, invalid

    /// 3–32 characters: lowercase letters, digits, or underscore, starting with a letter.
    public static func isValid(_ name: String) -> Bool {
        guard (3...32).contains(name.count), let first = name.first, first.isASCII, first.isLowercase else { return false }
        return name.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber || $0 == "_") }
    }
}

/// Account sign-up and linking. `SampleAccountService` now; the Go server later.
@MainActor
public protocol AccountService: AnyObject {
    func checkUsername(_ name: String) async -> UsernameCheck
    func sendEmailCode(to email: String) async
    func verifyEmail(code: String) async -> Bool
    func redeemInvite(code: String) async -> Bool
    var defaultServer: ServerPolicy { get }
    /// Shown as a QR code on a device waiting to be linked.
    var linkingCode: String { get }
}
