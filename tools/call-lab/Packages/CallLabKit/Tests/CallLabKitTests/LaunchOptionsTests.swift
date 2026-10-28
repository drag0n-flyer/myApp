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
