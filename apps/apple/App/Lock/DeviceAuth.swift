import AppCore
import LocalAuthentication

/// The system's own Face ID, Touch ID or passcode check (`LAContext`, `.deviceOwnerAuthentication`: biometrics with the
/// device passcode, or on the Mac the account password, as the fallback). Holds nothing: a new context per request,
/// no keychain item, no stored secret. Only a success reported by the system is a pass.
@MainActor
struct SystemDeviceAuth: DeviceAuth {
    func availability() -> DeviceAuthAvailability {
        let context = LAContext()
        var error: NSError?
        let can = context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
        let noPasscode = error.map { $0.domain == LAError.errorDomain && $0.code == LAError.Code.passcodeNotSet.rawValue } ?? false
        return DeviceAuthAvailability.from(canEvaluate: can, passcodeNotSet: noPasscode)
    }

    func authenticate(reason: String) async -> AuthResult {
        let context = LAContext()
        return await withCheckedContinuation { (continuation: CheckedContinuation<AuthResult, Never>) in
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, _ in
                // Every error (cancel, lockout, the app left the screen, ...) is "not passed".
                continuation.resume(returning: success ? .success : .cancelled)
            }
        }
    }
}
