import Foundation
import AVFoundation
import CallCore
import WebRTC

/// Camera capture and sender limits for a video tier. UNVERIFIED against WebRTC M156 (not compiled here).
/// Tier values come from CallCore (`MediaLogic.videoParameters`, which reads `VideoLadder`).
final class VideoController: @unchecked Sendable {
    private let source: RTCVideoSource
    private let track: RTCVideoTrack
    private let sender: RTCRtpSender
    private let capturer: RTCCameraVideoCapturer

    init(source: RTCVideoSource, track: RTCVideoTrack, sender: RTCRtpSender, capturer: RTCCameraVideoCapturer) {
        self.source = source; self.track = track; self.sender = sender; self.capturer = capturer
    }

    /// Camera format for `tier`: the exact size, else the nearest larger, else the largest (rule in `VideoFormatPicker`).
    static func format(for tier: VideoTier, available: [AVCaptureDevice.Format]) -> AVCaptureDevice.Format? {
        let sizes = available.map { f -> VideoSize in
            let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            return VideoSize(width: Int(d.width), height: Int(d.height))
        }
        return VideoFormatPicker.index(in: sizes, for: tier).map { available[$0] }
    }

    func setVideo(_ tier: VideoTier?) async {
        guard let tier else {
            track.isEnabled = false
            sender.track = nil   // no frames, so the peer sees the video stop
            await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in capturer.stopCapture { k.resume() } }
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
        let devices = RTCCameraVideoCapturer.captureDevices()
        guard let device = devices.first(where: { $0.position == .front }) ?? devices.first,
              let format = Self.format(for: tier, available: RTCCameraVideoCapturer.supportedFormats(for: device)) else { return }
        try? await capturer.startCapture(with: device, format: format, fps: p.fps)
    }
}
