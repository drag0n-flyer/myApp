import XCTest
@testable import CallLabKit

final class LaunchOptionsTests: XCTestCase {
    func testNotHeadlessGivesNil() throws {
        XCTAssertNil(try LabLaunchOptions.parse(["CallLabMac", "-NSDocumentRevisionsDebugMode", "YES"]))
    }

    func testParsesAllArguments() throws {
        let o = try XCTUnwrap(LabLaunchOptions.parse([
            "CallLabMac", "--headless", "--role", "caller", "--preset", "ultraLow", "--path", "relay",
            "--stimulus", "/tmp/s.wav", "--out", "/tmp/runs/a",
        ]))
        XCTAssertEqual(o, LabLaunchOptions(role: .caller, preset: .ultraLow, path: .relay,
                                           stimulus: "/tmp/s.wav", out: "/tmp/runs/a"))
        XCTAssertEqual(o.knobs.path, .relay)
        XCTAssertEqual(o.knobs.opusBitrate, LabPreset.ultraLow.knobs.opusBitrate)
    }

    func testDefaultsAndUnknownArgumentsIgnored() throws {
        let o = try XCTUnwrap(LabLaunchOptions.parse(["x", "-AppleLanguages", "(en)", "--headless", "--role", "callee"]))
        XCTAssertEqual(o, LabLaunchOptions(role: .callee, preset: .balanced, path: .direct, stimulus: nil, out: nil))
    }

    func testErrors() {
        XCTAssertThrowsError(try LabLaunchOptions.parse(["x", "--headless"]))                         // no role
        XCTAssertThrowsError(try LabLaunchOptions.parse(["x", "--headless", "--role", "boss"]))
        XCTAssertThrowsError(try LabLaunchOptions.parse(["x", "--headless", "--role", "caller", "--preset", "loud"]))
        XCTAssertThrowsError(try LabLaunchOptions.parse(["x", "--headless", "--role", "caller", "--path", "tor"]))
        XCTAssertThrowsError(try LabLaunchOptions.parse(["x", "--headless", "--role", "caller", "--out"]))   // no value
    }
}

/// The server's `httpx.Bytes` takes base64url without padding (`base64.RawURLEncoding`) and rejects
/// anything else with `bad_request` ("bad call frame").
final class PayloadEncodingTests: XCTestCase {
    func testEncodedIsBase64URLWithoutPadding() throws {
        // Long and varied enough that standard base64 would contain '+', '/' and '='.
        for n in 0..<40 {
            let sdp = String(repeating: "a=fmtp:111 minptime=10;useinbandfec=1~?>\n", count: n + 1) + String(repeating: "x", count: n)
            let s = try LabPayload(kind: .offer, sdp: sdp).encoded()
            XCTAssertFalse(s.contains("+") || s.contains("/") || s.contains("="), s)
            XCTAssertEqual(try LabPayload.decode(s).sdp, sdp)
        }
    }

    func testDecodesServerBase64URL() throws {
        // {"kind":"bye"} as base64url without padding, the way the server sends payloads.
        let json = Data(#"{"kind":"bye"}"#.utf8)
        let url = json.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        XCTAssertEqual(try LabPayload.decode(url).kind, .bye)
    }
}
