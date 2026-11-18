import Foundation
import AVFoundation
import AppCore
import CallCore
import CoreMedia
@preconcurrency import WebRTC

/// Camera capture and sender limits for a video tier. UNVERIFIED against WebRTC M156 (not compiled here).
/// Tier values come from CallCore (`MediaLogic.videoParameters`, which reads `VideoLadder`).
// @unchecked Sendable: the mutable fields (`position`, `running`, `lastTierStore`) are only touched under `lock`, the rest are immutable `let`s, and WebRTC's sender, source and capturer accept calls from any thread.
final class VideoController: @unchecked Sendable {
    private let source: RTCVideoSource
    private let track: RTCVideoTrack
    private let sender: RTCRtpSender
    private let capturer: RTCCameraVideoCapturer
    private let lock = NSLock()
    private var position: AVCaptureDevice.Position = .front   // behind `lock`
    private var running: Int?                                  // fps of the running capture; nil when stopped (behind `lock`)

    init(source: RTCVideoSource, track: RTCVideoTrack, sender: RTCRtpSender, capturer: RTCCameraVideoCapturer) {
        self.source = source; self.track = track; self.sender = sender; self.capturer = capturer
    }

    /// True when the device has a front and a back camera (iPhone). Always false on a Mac.
    static var canSwitchCamera: Bool {
        #if os(iOS)
        let devices = RTCCameraVideoCapturer.captureDevices()
        return devices.contains { $0.position == .front } && devices.contains { $0.position == .back }
        #else
        return false
        #endif
    }

    /// Camera format for `tier`: the exact size, else the nearest larger, else the largest (rule in `VideoFormatPicker`).
    static func format(for tier: VideoTier, available: [AVCaptureDevice.Format]) -> AVCaptureDevice.Format? {
        let sizes = available.map { f -> VideoSize in
            let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            return VideoSize(width: Int(d.width), height: Int(d.height))
        }
        return VideoFormatPicker.index(in: sizes, for: tier).map { available[$0] }
    }

    /// `ceiling` is the data mode in effect for the call; the tier is clamped to what it allows.
    func setVideo(_ requested: VideoTier?, ceiling: DataMode) async {
        guard let tier = MediaLogic.effectiveTier(requested, ceiling: ceiling) else {
            track.isEnabled = false
            sender.track = nil   // no frames, so the peer sees the video stop
            await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in capturer.stopCapture { k.resume() } }
            lock.withLock { running = nil }
            return
        }
        let p = MediaLogic.videoParameters(tier)
        let params = sender.parameters
        for e in params.encodings {
            e.maxBitrateBps = NSNumber(value: p.maxBitrate)
            e.maxFramerate = NSNumber(value: p.fps)
        }
        sender.parameters = params
        source.adaptOutputFormat(toWidth: Int32(p.width), height: Int32(p.height), fps: Int32(p.fps))
        track.isEnabled = true
        sender.track = track
        await startCapture(tier: tier, fps: p.fps, position: lock.withLock { position })
    }

    /// Restarts capture on the other camera at the same tier. Does nothing when capture is not running
    /// or the device has no second camera.
    func switchCamera() async {
        let (fps, current) = lock.withLock { (running, position) }
        guard let fps, let tier = lastTier else { return }
        let target: AVCaptureDevice.Position = current == .front ? .back : .front
        guard RTCCameraVideoCapturer.captureDevices().contains(where: { $0.position == target }) else { return }
        await startCapture(tier: tier, fps: fps, position: target)
    }

    private var lastTierStore: VideoTier?   // behind `lock`
    private var lastTier: VideoTier? { lock.withLock { lastTierStore } }

    private func startCapture(tier: VideoTier, fps: Int, position wanted: AVCaptureDevice.Position) async {
        let devices = RTCCameraVideoCapturer.captureDevices()
        guard let device = devices.first(where: { $0.position == wanted }) ?? devices.first,
              let format = Self.format(for: tier, available: RTCCameraVideoCapturer.supportedFormats(for: device)) else { return }
        do {
            try await capturer.startCapture(with: device, format: format, fps: fps)
            lock.withLock { position = device.position; running = fps; lastTierStore = tier }
        } catch {
            lock.withLock { running = nil }
        }
    }
}
