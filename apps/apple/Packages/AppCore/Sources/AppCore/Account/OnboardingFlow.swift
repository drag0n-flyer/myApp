public enum DevicePlatform: Sendable {
    case iPhone, mac
}

public enum ServerChoice: Equatable, Sendable {
    case defaultServer
    case invite(InviteLink)
    case address(String)
}

public enum OnboardingStep: Equatable, Sendable {
    case welcome, joinServer, inviteCode, createAccount, verifyEmail, createPasskey, permissions, done
    case linkDevice, lostDevice
}

/// Welcome and sign-up steps. Accounts are created on iPhone; the Mac only links (UI design §6.8).
public struct OnboardingFlow: Sendable {
    public let platform: DevicePlatform
    public private(set) var step: OnboardingStep = .welcome
    public private(set) var server: ServerChoice?
    private var history: [OnboardingStep] = []

    public init(platform: DevicePlatform) {
        self.platform = platform
    }

    public var canCreateAccount: Bool { platform == .iPhone }

    public mutating func getStarted() {
        guard step == .welcome, canCreateAccount else { return }
        go(.joinServer)
    }

    public mutating func alreadyHaveAccount() {
        guard step == .welcome else { return }
        go(.linkDevice)
    }

    public mutating func noOldDevice() {
        guard step == .linkDevice, canCreateAccount else { return }
        go(.lostDevice)
    }

    public mutating func choose(_ choice: ServerChoice, requiresInvite: Bool) {
        guard step == .joinServer else { return }
        server = choice
        switch choice {
        case .invite: go(.createAccount)
        case .defaultServer, .address: go(requiresInvite ? .inviteCode : .createAccount)
        }
    }

    public mutating func inviteAccepted() { advance(from: .inviteCode, to: .createAccount) }
    public mutating func accountSubmitted() { advance(from: .createAccount, to: .verifyEmail) }
    public mutating func emailVerified() { advance(from: .verifyEmail, to: .createPasskey) }
    public mutating func passkeyCreated() { advance(from: .createPasskey, to: .permissions) }
    public mutating func permissionsHandled() { advance(from: .permissions, to: .done) }

    public mutating func linked() {
        guard step == .linkDevice || step == .lostDevice else { return }
        go(.done)
    }

    public mutating func back() {
        guard step != .done, let previous = history.popLast() else { return }
        step = previous
    }

    private mutating func advance(from expected: OnboardingStep, to next: OnboardingStep) {
        guard step == expected else { return }
        go(next)
    }

    private mutating func go(_ next: OnboardingStep) {
        history.append(step)
        step = next
    }
}
