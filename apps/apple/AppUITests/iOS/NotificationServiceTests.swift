import SecureMessagingCore
import XCTest

/// Notification extension: a push for "Lunch?" from Ana shows `Ana` / `Lunch?` in the banner.
///
/// The push itself is delivered from the host with `xcrun simctl push <device> <bundle id> <file>`
/// (a UI test cannot spawn processes). `PushFixture.apnsJSON` builds the file: set
/// `TEST_RUNNER_NSE_PUSH_FILE` to a path and the test writes the file there before it waits for the
/// banner, and the CI step sends it. Needs the real engine switch from plans 4 and 6 for
/// `-fakeMLS` (the extension must decrypt with the same fake group state as the app).
@MainActor
final class NotificationServiceTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testPushShowsDecryptedPreview() async throws {
        throw XCTSkip("Needs I1: shared -fakeMLS engine state and a CI step that sends the push with xcrun simctl push")
        let app = XCUIApplication.launched(extra: ["-fakeMLS"])
        XCTAssertTrue(app.tab("Chats").waitToExist())

        let json = try await PushFixture.apnsJSON(text: "Lunch?", senderAccount: "ana", receiverAccount: "me")
        if let path = ProcessInfo.processInfo.environment["NSE_PUSH_FILE"] {
            try json.write(toFile: path, atomically: true, encoding: .utf8)
        }
        let attachment = XCTAttachment(string: json)
        attachment.lifetime = .keepAlways
        add(attachment)

        // Leave the app so the banner shows, then look for it on the home screen.
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let banner = springboard.otherElements["Notification"].firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 20))
        XCTAssertTrue(banner.staticTexts["Ana"].exists)
        XCTAssertTrue(banner.staticTexts["Lunch?"].exists)
    }
}

/// Builds the `simctl push` file: an `aps` alert with `mutable-content` and `d`, the padded inbox
/// payload as standard base64 (server design 7.4: a 2-byte length, the payload, zeros to a bucket).
enum PushFixture {
    static func apnsJSON(text: String, senderAccount: String, receiverAccount: String) async throws -> String {
        let sender = FakeMLSEngine(identity: "\(senderAccount)/d1")
        let receiver = FakeMLSEngine(identity: "\(receiverAccount)/d1")
        let group = try await sender.createGroup()
        let packages = try await receiver.makeKeyPackages(count: 1)
        let bundle = try await sender.add(keyPackages: packages, to: group)
        try await sender.commitAccepted(group)
        if let welcome = bundle.welcome { _ = try await receiver.process(welcome) }

        let envelope: [String: Any] = [
            "v": 1, "id": UUID().uuidString.lowercased(), "at": 1_700_000_000_000, "k": "text", "t": text,
        ]
        let plaintext = try JSONSerialization.data(withJSONObject: envelope)
        let message = try await sender.encrypt(plaintext, in: group).ciphertext

        var body = Data([UInt8(message.count >> 8), UInt8(message.count & 0xff)]) + message
        let bucket = [256, 512, 1024, 2048, 3072].first { $0 >= body.count } ?? 3072
        body += Data(repeating: 0, count: bucket - body.count)

        let push: [String: Any] = [
            "aps": ["alert": ["title": "[App]", "body": "New message"], "mutable-content": 1],
            "d": body.base64EncodedString(),
        ]
        return String(decoding: try JSONSerialization.data(withJSONObject: push, options: [.sortedKeys]), as: UTF8.self)
    }
}
