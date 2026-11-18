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

    func testSampleCaptureFollowsTheRealClockUntilLocked() throws {
        let capture = SampleVoiceCapture()
        try capture.start()
        XCTAssertLessThan(capture.elapsed, 1, "a hold and release in sample mode is as short as it really was")
        capture.recordingLocked()
        XCTAssertGreaterThanOrEqual(capture.elapsed, 2)
        capture.cancel()
    }

    func testSampleCaptureWritesAFileAndReportsAtLeastASecondOnceLocked() throws {
        let capture = SampleVoiceCapture()
        XCTAssertEqual(capture.permission, .granted)
        try capture.start()
        capture.recordingLocked()
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
