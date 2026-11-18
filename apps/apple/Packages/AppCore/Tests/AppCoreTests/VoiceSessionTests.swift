import XCTest
@testable import AppCore

@MainActor
private final class FakeCapture: VoiceCapture {
    var permission: VoicePermission = .granted
    var grantsOnRequest = true
    var level: Float = 0.5
    var elapsed: Double = 0
    var startCount = 0
    var cancelCount = 0
    var stopCount = 0
    var lockedCount = 0
    func recordingLocked() { lockedCount += 1 }
    var folder: URL
    var current: URL?

    init(folder: URL) { self.folder = folder }

    func requestPermission() async -> Bool {
        permission = grantsOnRequest ? .granted : .denied
        return grantsOnRequest
    }
    func start() throws {
        startCount += 1
        let url = folder.appendingPathComponent("voice-\(startCount).ogg")
        try Data([1]).write(to: url)
        current = url
    }
    func stop() throws -> (url: URL, duration: Double) {
        stopCount += 1
        guard let url = current else { throw CocoaError(.fileNoSuchFile) }
        current = nil
        return (url, elapsed)
    }
    func cancel() {
        cancelCount += 1
        if let url = current { try? FileManager.default.removeItem(at: url) }
        current = nil
    }
}

@MainActor
final class VoiceSessionTests: XCTestCase {
    private var folder: URL!
    private var capture: FakeCapture!
    private var session: VoiceSession!
    private var sent: [(URL, Double)] = []

    override func setUp() async throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("voice-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        capture = FakeCapture(folder: folder)
        sent = []
        session = VoiceSession(capture: capture, rtl: false, startDelay: .seconds(60), hintDuration: .seconds(60)) { [unowned self] url, d in
            self.sent.append((url, d))
        }
    }
    override func tearDown() async throws { try? FileManager.default.removeItem(at: folder) }

    private func files() -> [String] { ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []).sorted() }

    private func hold() async {
        session.press()
        await session.beginRecording()
    }

    func testHoldRecordsThenReleaseAtOneSecondSends() async {
        await hold()
        XCTAssertEqual(session.state, .recording)
        capture.elapsed = 1.0
        session.release()
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(session.state, .idle)
        XCTAssertEqual(files(), ["voice-1.ogg"], "the sent file is handed on, not deleted")
    }

    func testReleaseUnderOneSecondSendsNothingAndDeletesTheFile() async {
        await hold()
        capture.elapsed = 0.9
        session.release()
        XCTAssertTrue(sent.isEmpty)
        XCTAssertEqual(files(), [])
        XCTAssertTrue(session.hintVisible)
        XCTAssertEqual(session.state, .idle)
    }

    func testTapShowsTheHintWithoutRecording() async {
        session.press()
        session.release()
        XCTAssertEqual(capture.startCount, 0)
        XCTAssertTrue(session.hintVisible)
        XCTAssertEqual(session.state, .idle)
        await session.beginRecording()
        XCTAssertEqual(capture.startCount, 0, "the delayed start does nothing after a release")
    }

    func testSlideAwayThenReleaseDeletesTheFile() async {
        await hold()
        capture.elapsed = 5
        session.drag(dx: -120, dy: 0)
        XCTAssertEqual(session.state, .willCancel)
        session.release()
        XCTAssertTrue(sent.isEmpty)
        XCTAssertEqual(files(), [])
        XCTAssertEqual(session.state, .idle)
    }

    func testLockThenStopGivesAPreviewAndSendSends() async {
        await hold()
        capture.elapsed = 3
        session.drag(dx: 0, dy: -80)
        session.release()
        XCTAssertEqual(session.state, .locked)
        session.stop()
        XCTAssertEqual(session.state, .preview)
        XCTAssertEqual(session.previewDuration, 3)
        XCTAssertTrue(sent.isEmpty)
        session.send()
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(session.state, .idle)
        XCTAssertEqual(files(), ["voice-1.ogg"])
    }

    func testDeleteFromPreviewRemovesTheFile() async {
        await session.activateAccessible()
        XCTAssertEqual(session.state, .locked)
        capture.elapsed = 4
        session.stop()
        XCTAssertEqual(files(), ["voice-1.ogg"])
        session.delete()
        XCTAssertEqual(files(), [])
        XCTAssertTrue(sent.isEmpty)
        XCTAssertEqual(session.state, .idle)
    }

    func testDeleteFromLockedStopsAndRemoves() async {
        await session.activateAccessible()
        session.delete()
        XCTAssertEqual(files(), [])
        XCTAssertEqual(session.state, .idle)
    }

    func testSendingAShortLockedNoteIsRefused() async {
        await session.activateAccessible()
        capture.elapsed = 0.4
        session.send()
        XCTAssertTrue(sent.isEmpty)
        XCTAssertEqual(files(), [])
        XCTAssertTrue(session.hintVisible)
    }

    func testLimitAndInterruptionKeepAPreview() async {
        await hold()
        capture.elapsed = 1800
        session.tick()
        XCTAssertEqual(session.state, .preview, "at the limit it stops and is never sent by itself")
        XCTAssertTrue(sent.isEmpty)
        session.delete()

        await hold()
        capture.elapsed = 5
        session.interrupted()
        XCTAssertEqual(session.state, .preview)
        XCTAssertEqual(files(), ["voice-2.ogg"])
    }

    func testTickFeedsAScrollingMeterOfThirtyBars() async {
        await hold()
        for i in 0..<40 {
            capture.level = Float(i) / 40
            capture.elapsed = Double(i) / 10
            session.tick()
        }
        XCTAssertEqual(session.levels.count, VoiceSession.barCount)
        XCTAssertEqual(session.levels.last ?? -1, 39.0 / 40, accuracy: 0.001)
        XCTAssertEqual(session.elapsed, 3.9, accuracy: 0.001)
    }

    func testFirstPressThatAsksForPermissionDoesNotRecord() async {
        capture.permission = .undetermined
        await hold()
        XCTAssertEqual(capture.startCount, 0)
        XCTAssertEqual(session.state, .idle)
        XCTAssertFalse(session.denied)
    }

    func testDeniedPermissionShowsTheAlertState() async {
        capture.permission = .denied
        await hold()
        XCTAssertEqual(capture.startCount, 0)
        XCTAssertTrue(session.denied)
        XCTAssertEqual(session.state, .idle)
        session.dismissDenied()
        XCTAssertFalse(session.denied)
    }

    func testUndeterminedThenRefusedIsDenied() async {
        capture.permission = .undetermined
        capture.grantsOnRequest = false
        await hold()
        XCTAssertTrue(session.denied)
    }

    func testDiscardRemovesARecordingAndAPreview() async {
        await hold()
        session.discard()
        XCTAssertEqual(files(), [])
        XCTAssertEqual(session.state, .idle)

        await hold()
        session.interrupted()
        XCTAssertEqual(session.state, .preview)
        session.discard()
        XCTAssertEqual(files(), [])
        XCTAssertNil(session.previewURL)
        XCTAssertTrue(sent.isEmpty)
    }

    func testRightToLeftSessionCancelsTowardTheTrailingSideOfLTR() async {
        session.rtl = true
        await hold()
        session.drag(dx: 110, dy: 0)
        XCTAssertEqual(session.state, .willCancel)
    }

    func testACancelledTouchWhileRecordingKeepsAPreviewAndSendsNothing() async {
        await hold()
        capture.elapsed = 5
        session.touchEndedWithoutRelease()
        XCTAssertEqual(session.state, .preview)
        XCTAssertTrue(sent.isEmpty)
        XCTAssertEqual(files(), ["voice-1.ogg"])
        session.delete()
    }

    func testACancelledTouchBeforeRecordingStartsReturnsToIdleAndAllowsAnotherPress() async {
        session.press()
        session.touchEndedWithoutRelease()
        XCTAssertEqual(session.state, .idle)
        await hold()
        XCTAssertEqual(session.state, .recording, "the next touch works")
    }

    func testACancelledTouchLeavesLockedAndPreviewAlone() async {
        await session.activateAccessible()
        session.touchEndedWithoutRelease()
        XCTAssertEqual(session.state, .locked)
        capture.elapsed = 2
        session.stop()
        session.touchEndedWithoutRelease()
        XCTAssertEqual(session.state, .preview)
    }

    func testTheCaptureIsToldWhenTheRecordingLocks() async {
        await hold()
        capture.elapsed = 3
        session.drag(dx: 0, dy: -80)
        session.release()
        XCTAssertEqual(capture.lockedCount, 1)
        session.delete()
        await session.activateAccessible()
        XCTAssertEqual(capture.lockedCount, 2)
    }

    func testOrphanRecordingsAreRemovedAtStart() throws {
        for name in ["voice-a.caf", "voice-b.ogg", "keep.txt"] {
            try Data([1]).write(to: folder.appendingPathComponent(name))
        }
        VoiceSession.removeOrphans(in: folder)
        XCTAssertEqual(files(), ["keep.txt"])
    }
}
