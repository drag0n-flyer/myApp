import SwiftUI
import SecureMessaging

/// Launched with `-prepare`, the host plays "the app": it sets up a group between a sender and
/// the receiver `proto/nse` (whose storage is in the app group container, shared with the
/// extension), encrypts "hello from MLS", pads it like the server, and writes `push.apns` to
/// the container for `xcrun simctl push`.
@main
struct HostApp: App {
    init() {
        if CommandLine.arguments.contains("-prepare") {
            Task { await Self.prepare() }
        }
    }

    var body: some Scene {
        WindowGroup { Text("NSE prototype host") }
    }

    static func prepare() async {
        let groupID = "group.org.example.app.nseproto"
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID) else {
            return
        }
        let result = container.appendingPathComponent("prepare-result.txt")
        do {
            let sender = try makeMLSEngine(
                identity: "proto/sender",
                storage: FileManager.default.temporaryDirectory.appendingPathComponent("mls-sender"))
            let receiver = try makeMLSEngine(identity: "proto/nse", storage: container.appendingPathComponent("mls"))
            let group = try await sender.createGroup()
            let bundle = try await sender.add(keyPackages: try await receiver.makeKeyPackages(count: 1), to: group)
            try await sender.commitAccepted(group)
            guard let welcome = bundle.welcome else { throw MLSError.malformed }
            _ = try await receiver.process(welcome)
            let (ciphertext, _) = try await sender.encrypt(Data("hello from MLS".utf8), in: group)
            let padded = pad(ciphertext)
            let push: [String: Any] = [
                "aps": ["alert": ["title": "[App]", "body": "New message"], "mutable-content": 1],
                "d": padded.base64EncodedString(),
                "Simulator Target Bundle": "org.example.app.nseproto",
            ]
            try JSONSerialization.data(withJSONObject: push).write(to: container.appendingPathComponent("push.apns"))
            try Data("OK".utf8).write(to: result)
        } catch {
            try? Data("ERR \(error)".utf8).write(to: result)
        }
    }

    /// Server padding (spec section 7.4): 2-byte big-endian length, payload, zeros to a bucket size.
    static func pad(_ payload: Data) -> Data {
        let size = [256, 512, 1024, 2048, 3072].first { payload.count + 2 <= $0 } ?? 3072
        var out = Data(count: size)
        out[0] = UInt8(payload.count >> 8)
        out[1] = UInt8(payload.count & 0xff)
        out.replaceSubrange(2..<(2 + payload.count), with: payload)
        return out
    }
}
