import XCTest
@testable import AppCore

@MainActor
final class VoiceLevelTests: XCTestCase {
    func testDecibelsMapToZeroToOne() {
        XCTAssertEqual(VoiceLevel.normalized(decibels: 0), 1, accuracy: 0.001)
        XCTAssertEqual(VoiceLevel.normalized(decibels: -25), 0.5, accuracy: 0.001)
        XCTAssertEqual(VoiceLevel.normalized(decibels: -50), 0, accuracy: 0.001)
    }

    func testOutOfRangeAndSilenceAreClamped() {
        XCTAssertEqual(VoiceLevel.normalized(decibels: -160), 0)
        XCTAssertEqual(VoiceLevel.normalized(decibels: 6), 1)
        XCTAssertEqual(VoiceLevel.normalized(decibels: -.infinity), 0)
        XCTAssertEqual(VoiceLevel.normalized(decibels: .nan), 0)
    }

    func testSampleCaptureWritesAFileAndReportsAtLeastASecond() throws {
        let capture = SampleVoiceCapture()
        XCTAssertEqual(capture.permission, .granted)
        try capture.start()
        let result = try capture.stop()
        XCTAssertGreaterThanOrEqual(result.duration, 2)
        XCTAssertTrue(FileManager.default.fileExists(atPath: result.url.path))
        try FileManager.default.removeItem(at: result.url)
    }

    func testSampleCaptureCancelDeletesTheFile() throws {
        let capture = SampleVoiceCapture()
        try capture.start()
        let url = try XCTUnwrap(capture.currentURL)
        capture.cancel()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }
}
