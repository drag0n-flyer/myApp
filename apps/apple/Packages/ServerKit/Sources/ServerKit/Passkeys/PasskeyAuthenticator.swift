#if canImport(AuthenticationServices)
import AppCore
import AuthenticationServices
import Foundation
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Platform passkeys through `ASAuthorizationController`. Cancelling maps to `.cancelled`;
/// every other failure maps to `.passkeyUnavailable`.
@MainActor
public final class PasskeyAuthenticator: NSObject, PasskeyAuthenticating, ASAuthorizationControllerDelegate,
                                         ASAuthorizationControllerPresentationContextProviding {
    /// Sendable results, built inside the delegate callback so no `ASAuthorization` crosses isolation.
    private enum Outcome: Sendable {
        case registration(RegistrationCredential)
        case assertion(AssertionCredential)
    }

    private struct PasskeyFailure: Error {}

    private var pending: (controller: ASAuthorizationController, continuation: CheckedContinuation<Outcome, Error>)?

    public override init() { super.init() }

    public func register(_ options: CreationOptions) async throws(AccountError) -> RegistrationCredential {
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: options.rpID)
        let request = provider.createCredentialRegistrationRequest(challenge: options.challenge, name: options.userName,
                                                                   userID: options.userID)
        request.userVerificationPreference = .required
        guard case .registration(let credential) = try await perform([request]) else { throw .passkeyUnavailable }
        return credential
    }

    public func assert(_ options: RequestOptions) async throws(AccountError) -> AssertionCredential {
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: options.rpID)
        let request = provider.createCredentialAssertionRequest(challenge: options.challenge)
        request.userVerificationPreference = .required
        guard case .assertion(let credential) = try await perform([request]) else { throw .passkeyUnavailable }
        return credential
    }

    /// One request at a time: a second call while one is pending fails instead of replacing it.
    private func perform(_ requests: [ASAuthorizationRequest]) async throws(AccountError) -> Outcome {
        guard pending == nil else { throw .passkeyUnavailable }
        do {
            return try await withCheckedThrowingContinuation { (c: CheckedContinuation<Outcome, Error>) in
                let controller = ASAuthorizationController(authorizationRequests: requests)
                controller.delegate = self
                controller.presentationContextProvider = self
                pending = (controller, c)
                controller.performRequests()
            }
        } catch {
            if (error as? ASAuthorizationError)?.code == .canceled { throw .cancelled }
            throw .passkeyUnavailable
        }
    }

    /// Ignores callbacks from any controller other than the current one.
    private func finish(_ controller: ObjectIdentifier, _ result: Result<Outcome, Error>) {
        guard let current = pending, ObjectIdentifier(current.controller) == controller else { return }
        pending = nil
        current.continuation.resume(with: result)
    }

    nonisolated private static func outcome(from authorization: ASAuthorization) -> Outcome? {
        switch authorization.credential {
        case let c as ASAuthorizationPlatformPublicKeyCredentialRegistration:
            guard let attestation = c.rawAttestationObject else { return nil }
            return .registration(RegistrationCredential(id: c.credentialID, clientDataJSON: c.rawClientDataJSON,
                                                        attestationObject: attestation))
        case let c as ASAuthorizationPlatformPublicKeyCredentialAssertion:
            return .assertion(AssertionCredential(id: c.credentialID, clientDataJSON: c.rawClientDataJSON,
                                                  authenticatorData: c.rawAuthenticatorData, signature: c.signature,
                                                  userHandle: c.userID))
        default:
            return nil
        }
    }

    // The system calls these on the main thread.
    nonisolated public func authorizationController(controller: ASAuthorizationController,
                                                    didCompleteWithAuthorization authorization: ASAuthorization) {
        let outcome = Self.outcome(from: authorization)
        let id = ObjectIdentifier(controller)
        MainActor.assumeIsolated { finish(id, outcome.map { .success($0) } ?? .failure(PasskeyFailure())) }
    }

    nonisolated public func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let id = ObjectIdentifier(controller)
        MainActor.assumeIsolated { finish(id, .failure(error)) }
    }

    nonisolated public func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            #if canImport(UIKit)
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
            #else
            return NSApplication.shared.keyWindow ?? ASPresentationAnchor()
            #endif
        }
    }
}
#endif
