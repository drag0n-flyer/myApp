import XCTest
@testable import AppCore

final class VoiceGestureTests: XCTestCase {
    private func run(_ events: [VoiceGesture.Event], from start: VoiceGesture.State = .idle, rtl: Bool = false) -> VoiceGesture.State {
        events.reduce(start) { VoiceGesture.step($0, $1, rtl: rtl) }
    }
    private let held: [VoiceGesture.Event] = [.press, .heldFor(ms: 150)]

    func testConstants() {
        XCTAssertEqual(VoiceGesture.startDelayMs, 150)
        XCTAssertEqual(VoiceGesture.minimumMs, 1000)
        XCTAssertEqual(VoiceGesture.cancelDistance, 100)
        XCTAssertEqual(VoiceGesture.lockDistance, 70)
    }

    func testHoldStartsRecordingOnlyAfterTheDelay() {
        XCTAssertEqual(run([.press]), .pending)
        XCTAssertEqual(run([.press, .heldFor(ms: 149)]), .pending)
        XCTAssertEqual(run(held), .recording)
    }

    func testReleaseBeforeTheDelayIsTooShort() {
        XCTAssertEqual(run([.press, .release(elapsedMs: 100)]), .tooShort)
    }

    func testReleaseAtTheMinimum() {
        XCTAssertEqual(run(held + [.release(elapsedMs: 999)]), .tooShort)
        XCTAssertEqual(run(held + [.release(elapsedMs: 1000)]), .send)
    }

    func testSlideToCancel() {
        XCTAssertEqual(run(held + [.drag(dx: -100, dy: 0)]), .willCancel)
        XCTAssertEqual(run(held + [.drag(dx: -100, dy: 0), .release(elapsedMs: 5000)]), .cancelled)
        XCTAssertEqual(run(held + [.drag(dx: -100, dy: 0), .drag(dx: -99, dy: 0)]), .recording)
        XCTAssertEqual(run(held + [.drag(dx: -99, dy: 0)]), .recording)
    }

    func testRightToLeftMirrors() {
        XCTAssertEqual(run(held + [.drag(dx: 100, dy: 0)], rtl: true), .willCancel)
        XCTAssertEqual(run(held + [.drag(dx: -100, dy: 0)], rtl: true), .recording)
        XCTAssertEqual(run(held + [.drag(dx: 99, dy: 0)], rtl: true), .recording)
        XCTAssertEqual(run(held + [.drag(dx: 100, dy: 0)], rtl: false), .recording)
    }

    func testSlideUpToLock() {
        XCTAssertEqual(run(held + [.drag(dx: 0, dy: -70)]), .willLock)
        XCTAssertEqual(run(held + [.drag(dx: 0, dy: -69)]), .recording)
        XCTAssertEqual(run(held + [.drag(dx: 0, dy: -70), .release(elapsedMs: 200)]), .locked)
        XCTAssertEqual(run(held + [.drag(dx: 0, dy: -70), .drag(dx: 0, dy: -10)]), .recording)
    }

    func testCancelWinsWhenBothDistancesArePassed() {
        XCTAssertEqual(run(held + [.drag(dx: -100, dy: -70)]), .willCancel)
        XCTAssertEqual(run(held + [.drag(dx: -100, dy: -70), .release(elapsedMs: 3000)]), .cancelled)
    }

    func testLockedStopThenSendOrDelete() {
        XCTAssertEqual(run([.stop], from: .locked), .preview)
        XCTAssertEqual(run([.sendTapped], from: .locked), .send)
        XCTAssertEqual(run([.sendTapped], from: .preview), .send)
        XCTAssertEqual(run([.delete], from: .locked), .cancelled)
        XCTAssertEqual(run([.delete], from: .preview), .cancelled)
    }

    func testLimitAndInterruptionBecomeAPreviewNeverASend() {
        for s in [VoiceGesture.State.recording, .willCancel, .willLock, .locked] {
            XCTAssertEqual(run([.limitReached], from: s), .preview)
            XCTAssertEqual(run([.interrupted], from: s), .preview)
        }
        XCTAssertEqual(run([.limitReached], from: .idle), .idle)
    }

    func testAccessibleActivationLocksFromIdleOnly() {
        XCTAssertEqual(run([.activateAccessible]), .locked)
        XCTAssertEqual(run([.activateAccessible], from: .recording), .recording)
    }

    func testDragIsIgnoredOutsideARecording() {
        XCTAssertEqual(run([.drag(dx: -200, dy: -200)], from: .locked), .locked)
        XCTAssertEqual(run([.drag(dx: -200, dy: -200)], from: .idle), .idle)
    }
}
