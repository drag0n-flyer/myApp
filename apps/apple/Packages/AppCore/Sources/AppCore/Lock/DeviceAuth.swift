import Foundation

/// How a request to the device's own authentication ended. Only `success` is a pass.
public enum AuthResult: Equatable, Sendable {
    case success
    /// The person dismissed the prompt, or it failed or closed (too many tries, lockout, the app left the screen).
    case cancelled
    /// The device cannot ask.
    case unavailable
}

/// Whether the device can ask for authentication.
public enum DeviceAuthAvailability: Equatable, Sendable {
    case ready
    /// The device has no passcode (any more): there is nothing to protect the app with. Only this exact case.
    case passcodeNotSet
    /// Anything else that stops the system from asking.
    case unavailable

    /// The one place the system's answer to "can you ask?" becomes a case: `passcodeNotSet` is true only for the
    /// system's own "passcode not set" error.
    public static func from(canEvaluate: Bool, passcodeNotSet: Bool) -> DeviceAuthAvailability {
        if canEvaluate { return .ready }
        return passcodeNotSet ? .passcodeNotSet : .unavailable
    }
}

/// The device's own Face ID, Touch ID or passcode check. The app keeps no PIN and no secret of its own: the system
/// prompt is the only way to pass. A small protocol so tests use `SampleDeviceAuth`.
@MainActor
public protocol DeviceAuth {
    func availability() -> DeviceAuthAvailability
    /// Shows the system prompt. Anything but a success from the system is not a pass.
    func authenticate(reason: String) async -> AuthResult
}

public extension DeviceAuth {
    var available: Bool { availability() == .ready }
}

/// For sample data and tests only: no system prompt. Answers with the next of `results` (default success).
/// The live app never builds it: `AppModel` picks it only in sample-data mode.
@MainActor
public final class SampleDeviceAuth: DeviceAuth {
    public var state: DeviceAuthAvailability
    private var queue: [AuthResult]
    public private(set) var requests = 0

    public init(state: DeviceAuthAvailability = .ready, results: [AuthResult] = []) {
        self.state = state
        self.queue = results
    }

    public func answerWith(_ results: AuthResult...) { queue = results }

    public func availability() -> DeviceAuthAvailability { state }

    public func authenticate(reason: String) async -> AuthResult {
        requests += 1
        if state != .ready { return .unavailable }
        return queue.isEmpty ? .success : queue.removeFirst()
    }
}
