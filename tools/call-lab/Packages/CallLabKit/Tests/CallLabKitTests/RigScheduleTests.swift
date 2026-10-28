import XCTest
@testable import CallLabKit

final class RigScheduleTests: XCTestCase {
    func testTrimDropsLeadInAndTail() {
        let rec = (0..<480_000).map { Int16(truncatingIfNeeded: $0) }
        let out = RigSchedule.trim(recorded: rec, sampleRate: 48_000, leadIn: 2, duration: 5)
        XCTAssertEqual(out.count, 240_000)
        XCTAssertEqual(out.first, Int16(truncatingIfNeeded: 96_000))
        XCTAssertEqual(out.last, Int16(truncatingIfNeeded: 335_999))
    }

    func testTrimShortRecordingKeepsWhatExists() {
        let rec = [Int16](repeating: 1, count: 100_000)
        XCTAssertEqual(RigSchedule.trim(recorded: rec, sampleRate: 48_000, leadIn: 2, duration: 5).count, 4_000)
        XCTAssertTrue(RigSchedule.trim(recorded: [1, 2, 3], sampleRate: 48_000, leadIn: 2, duration: 5).isEmpty)
    }
}
