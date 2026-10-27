#if canImport(UserNotifications) && canImport(CryptoKit)
import Foundation
#if os(iOS) && canImport(PushKit)
import PushKit
#endif

/// Seals APNs tokens to the gateway key and registers them with the server. Never logs tokens.
@MainActor
public final class PushRegistrar {
    private let client: ServerClient
    private let gatewayKey: Data
    private let environment: String
    private let bundleID: String
    #if os(iOS) && canImport(PushKit)
    private var voip: VoIPDelegate?
    #endif

    public init(client: ServerClient, gatewayKey: Data, environment: String, bundleID: String) {
        self.client = client
        self.gatewayKey = gatewayKey
        self.environment = environment
        self.bundleID = bundleID
    }

    public func didRegister(alertToken: Data) async {
        await register(token: alertToken, kind: .alert)
    }

    private func register(token: Data, kind: PushTokenKind) async {
        let handle = PushHandle(t: PushHandle.hex(token), k: kind.rawValue, e: environment, b: bundleID)
        guard let sealed = try? PushHandleSealer.seal(handle, gatewayKey: gatewayKey) else { return }
        // A failure is retried at the next launch; nothing about the token is logged.
        try? await client.putPushToken(kind: kind, sealed: sealed)
    }

    /// Deletes both handles (sign-out).
    public func unregisterAll() async {
        try? await client.deletePushToken(kind: .alert)
        try? await client.deletePushToken(kind: .voip)
    }

    #if os(iOS) && canImport(PushKit)
    /// Stays unused until plan 8 supplies a handler that reports the call to CallKit before returning.
    public func enableVoIP(onIncoming: @escaping @Sendable (PKPushPayload) -> Void) {
        let delegate = VoIPDelegate(registrar: self, onIncoming: onIncoming)
        voip = delegate
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = delegate
        registry.desiredPushTypes = [.voIP]
        delegate.registry = registry
    }

    fileprivate func didRegister(voipToken: Data) async {
        await register(token: voipToken, kind: .voip)
    }
    #endif
}

#if os(iOS) && canImport(PushKit)
private final class VoIPDelegate: NSObject, PKPushRegistryDelegate, @unchecked Sendable {
    weak var registrar: PushRegistrar?
    var registry: PKPushRegistry?
    let onIncoming: @Sendable (PKPushPayload) -> Void

    init(registrar: PushRegistrar, onIncoming: @escaping @Sendable (PKPushPayload) -> Void) {
        self.registrar = registrar
        self.onIncoming = onIncoming
    }

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let token = pushCredentials.token
        Task { @MainActor [weak self] in await self?.registrar?.didRegister(voipToken: token) }
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                      for type: PKPushType, completion: @escaping () -> Void) {
        onIncoming(payload)
        completion()
    }
}
#endif
#endif
