import XCTest
@testable import Messaging

final class MediaPlanTests: XCTestCase {
    func testPhotoTargetSize() {
        XCTAssertTrue(MediaPlan.targetSize(width: 4000, height: 3000) == (1600, 1200))
        XCTAssertTrue(MediaPlan.targetSize(width: 3000, height: 4000) == (1200, 1600))
        XCTAssertTrue(MediaPlan.targetSize(width: 800, height: 600) == (800, 600))   // never enlarged
        XCTAssertTrue(MediaPlan.targetSize(width: 1600, height: 1600) == (1600, 1600))
        XCTAssertTrue(MediaPlan.targetSize(width: 10_000, height: 3) == (1600, 1))
        XCTAssertTrue(MediaPlan.targetSize(width: 32, height: 16, maxSide: 32) == (32, 16))
    }

    func testThumbnailQualitySteps() {
        XCTAssertEqual(MediaPlan.thumbnailQualities(), [0.4, 0.3, 0.2, 0.1])
        XCTAssertEqual(MediaPlan.thumbnailSide, 32)
        XCTAssertEqual(MediaPlan.thumbnailMaxBytes, 1536)
    }

    func testPhotoSettings() {
        XCTAssertEqual(MediaPlan.photoMaxSide, 1600)
        XCTAssertEqual(MediaPlan.photoQuality, 0.7)
    }

    func testVoicePlan() {
        XCTAssertEqual(MediaPlan.Voice.sampleRate, 16_000)
        XCTAssertEqual(MediaPlan.Voice.channels, 1)
        XCTAssertEqual(MediaPlan.Voice.bitRate, 16_000)
        XCTAssertEqual(MediaPlan.Voice.outcome(duration: 0.2), .tooShort)
        XCTAssertEqual(MediaPlan.Voice.outcome(duration: 0.5), .send)
        XCTAssertEqual(MediaPlan.Voice.clamped(9_999), 1_800)
        XCTAssertEqual(MediaPlan.Voice.clamped(-1), 0)
    }

    func testAutoStoppedAtLimitIsKept() {
        // The recorder reports 0 after stopping itself at the limit; the measured time is used.
        let d = MediaPlan.Voice.finalDuration(recorderTime: 0, measured: 1_800.4)
        XCTAssertEqual(d, 1_800)
        XCTAssertEqual(MediaPlan.Voice.outcome(duration: d), .send)
        XCTAssertEqual(MediaPlan.Voice.finalDuration(recorderTime: 3.2, measured: 3.3), 3.3, accuracy: 0.0001)
        XCTAssertEqual(MediaPlan.Voice.outcome(duration: MediaPlan.Voice.finalDuration(recorderTime: 0, measured: 0.1)), .tooShort)
    }
}
