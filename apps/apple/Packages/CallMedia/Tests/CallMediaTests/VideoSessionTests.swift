@preconcurrency import XCTest
import AppCore
import CallCore
@preconcurrency import WebRTC
@testable import CallMedia

/// Needs the WebRTC XCFramework (Mac CI).
final class VideoSessionTests: XCTestCase {
    final class Sink: NSObject, RTCVideoRenderer, @unchecked Sendable {
        func setSize(_ size: CGSize) {}
        func renderFrame(_ frame: RTCVideoFrame?) {}
    }

    /// A session with no ICE servers configures, and closing it finishes the event stream.
    func testConfigureWithoutIceServersThenCloseFinishesEvents() async throws {
        let s = WebRTCMediaSession()
        try await s.configure(iceServers: [], policy: .all, video: false)
        await s.close()
        var n = 0
        for await _ in s.events { n += 1 }   // returns only if the stream is finished
        XCTAssertGreaterThanOrEqual(n, 0)
    }

    func testRenderersAttachAndDetach() async throws {
        let s = WebRTCMediaSession()
        try await s.configure(iceServers: [], policy: .all, video: true)
        let remote = Sink(), local = Sink()
        s.addRemoteRenderer(remote); s.addLocalRenderer(local)
        XCTAssertEqual(s.rendererCountsForTesting.remote, 1)
        XCTAssertEqual(s.rendererCountsForTesting.local, 1)
        s.removeRemoteRenderer(remote); s.removeLocalRenderer(local)
        XCTAssertEqual(s.rendererCountsForTesting.remote, 0)
        XCTAssertEqual(s.rendererCountsForTesting.local, 0)
        await s.close()
    }

    /// With the camera not running (video never turned on) switching does nothing and does not crash; a Mac cannot switch at all.
    func testSwitchCameraWithoutCaptureIsSafe() async throws {
        let s = WebRTCMediaSession()
        try await s.configure(iceServers: [], policy: .all, video: true)
        await s.switchCamera()
        #if os(macOS)
        XCTAssertFalse(WebRTCMediaSession.canSwitchCamera)
        #endif
        await s.close()
    }

    func testRendererOnAudioOnlySessionIsHarmless() async throws {
        let s = WebRTCMediaSession()
        try await s.configure(iceServers: [], policy: .all, video: false)
        let r = Sink()
        s.addLocalRenderer(r); s.addRemoteRenderer(r)
        await s.switchCamera()
        await s.close()
    }
}
