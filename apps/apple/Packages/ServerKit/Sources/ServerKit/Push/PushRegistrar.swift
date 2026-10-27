#if canImport(UserNotifications) && canImport(CryptoKit)
import Foundation
import os

/// Seals APNs tokens to the gateway key and registers them with the server. Never logs tokens.
@MainActor
public final class PushRegistrar {
    private let client: ServerClient
    private let gatewayKey: Data
    private let environment: String
    private let bundleID: String
    private let log = Logger(subsystem: "org.example.app", category: "push")

    public init(client: ServerClient, gatewayKey: Data, environment: String, bundleID: String) {
        self.client = client
        self.gatewayKey = gatewayKey
        self.environment = environment
        self.bundleID = bundleID
    }

    /// Returns whether the server accepted the handle. Failures are logged by kind and error code only
    /// (never the token or handle); the app retries at the next launch.
    @discardableResult
    public func didRegister(alertToken: Data) async -> Bool {
        await register(token: alertToken, kind: .alert)
    }

    private func register(token: Data, kind: PushTokenKind) async -> Bool {
        let handle = PushHandle(t: PushHandle.hex(token), k: kind.rawValue, e: environment, b: bundleID)
        let sealed: Data
        do {
            sealed = try PushHandleSealer.seal(handle, gatewayKey: gatewayKey)
        } catch {
            // Usually a bad compiled-in gateway key.
            log.error("push \(kind.rawValue, privacy: .public): sealing failed (\(String(describing: type(of: error)), privacy: .public))")
            return false
        }
        do {
            try await client.putPushToken(kind: kind, sealed: sealed)
            return true
        } catch {
            log.error("push \(kind.rawValue, privacy: .public): register failed (status \(error.status), \(error.code, privacy: .public))")
            return false
        }
    }

    /// Deletes both handles (sign-out). Returns whether both deletes succeeded.
    @discardableResult
    public func unregisterAll() async -> Bool {
        var ok = true
        for kind in [PushTokenKind.alert, .voip] {
            do {
                try await client.deletePushToken(kind: kind)
            } catch {
                ok = false
                log.error("push \(kind.rawValue, privacy: .public): unregister failed (status \(error.status), \(error.code, privacy: .public))")
            }
        }
        return ok
    }

    /// Seals and uploads the VoIP token (kind `voip`). Called by the app's `VoIPPushCenter`, which owns the PKPushRegistry.
    @discardableResult
    public func didRegister(voipToken: Data) async -> Bool {
        await register(token: voipToken, kind: .voip)
    }
}

#endif
