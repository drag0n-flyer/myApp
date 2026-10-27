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
    private var continuation: CheckedContinuation<ASAuthorization, Error>?
    private var controller: ASAuthorizationController?

    public override init() { super.init() }

    public func register(_ options: CreationOptions) async throws(AccountError) -> RegistrationCredential {
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: options.rpID)
        let request = provider.createCredentialRegistrationRequest(challenge: options.challenge, name: options.userName,
                                                                   userID: options.userID)
        request.userVerificationPreference = .required
        let result = try await perform([request])
        guard let c = result.credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration,
              let attestation = c.rawAttestationObject else { throw .passkeyUnavailable }
        return RegistrationCredential(id: c.credentialID, clientDataJSON: c.rawClientDataJSON, attestationObject: attestation)
    }

    public func assert(_ options: RequestOptions) async throws(AccountError) -> AssertionCredential {
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: options.rpID)
        let request = provider.createCredentialAssertionRequest(challenge: options.challenge)
        request.userVerificationPreference = .required
        let result = try await perform([request])
        guard let c = result.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion else { throw .passkeyUnavailable }
        return AssertionCredential(id: c.credentialID, clientDataJSON: c.rawClientDataJSON,
                                   authenticatorData: c.rawAuthenticatorData, signature: c.signature, userHandle: c.userID)
    }

    private func perform(_ requests: [ASAuthorizationRequest]) async throws(AccountError) -> ASAuthorization {
        do {
            return try await withCheckedThrowingContinuation { (c: CheckedContinuation<ASAuthorization, Error>) in
                continuation = c
                let controller = ASAuthorizationController(authorizationRequests: requests)
                controller.delegate = self
                controller.presentationContextProvider = self
                self.controller = controller
                controller.performRequests()
            }
        } catch {
            if (error as? ASAuthorizationError)?.code == .canceled { throw .cancelled }
            throw .passkeyUnavailable
        }
    }

    private func finish(_ result: Result<ASAuthorization, Error>) {
        let c = continuation
        continuation = nil
        controller = nil
        c?.resume(with: result)
    }

    // The system calls these on the main thread.
    nonisolated public func authorizationController(controller: ASAuthorizationController,
                                                    didCompleteWithAuthorization authorization: ASAuthorization) {
        MainActor.assumeIsolated { finish(.success(authorization)) }
    }

    nonisolated public func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        MainActor.assumeIsolated { finish(.failure(error)) }
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
