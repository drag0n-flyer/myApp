import Foundation

/// Sizing, quality and recording decisions (Design 9) as pure values and functions, so they are
/// tested on Linux. `MediaPreparation` (ImageIO) and `VoiceRecorder` (AVFoundation) call them.
public enum MediaPlan {
    /// Photos are re-encoded to JPEG with a longest side of at most this many pixels.
    public static let photoMaxSide = 1_600
    public static let photoQuality = 0.7

    /// Inline thumbnail: longest side, starting JPEG quality, step, floor and byte cap.
    public static let thumbnailSide = 32
    public static let thumbnailQuality = 0.4
    public static let thumbnailQualityStep = 0.1
    public static let thumbnailMinQuality = 0.1
    public static let thumbnailMaxBytes = 1_536

    /// Output size for a photo: the longest side is capped at `maxSide`, the aspect ratio is kept,
    /// and a smaller image is never enlarged. Each side is at least 1.
    public static func targetSize(width: Int, height: Int, maxSide: Int = photoMaxSide) -> (w: Int, h: Int) {
        guard width > 0, height > 0, maxSide > 0 else { return (max(width, 0), max(height, 0)) }
        let longest = max(width, height)
        if longest <= maxSide { return (width, height) }
        let scale = Double(maxSide) / Double(longest)
        return (max(1, Int((Double(width) * scale).rounded())), max(1, Int((Double(height) * scale).rounded())))
    }

    /// JPEG qualities to try for the thumbnail, best first: 0.4, 0.3, 0.2, 0.1.
    public static func thumbnailQualities() -> [Double] {
        var out: [Double] = []
        var q = thumbnailQuality
        while q >= thumbnailMinQuality - 1e-9 {
            out.append((q * 10).rounded() / 10)
            q -= thumbnailQualityStep
        }
        return out
    }

    /// Voice notes: Opus in CAF, 16 kHz mono, 16 kbps (Design 9).
    public enum Voice {
        public static let sampleRate = 16_000.0
        public static let channels = 1
        public static let bitRate = 16_000
        public static let fileExtension = "caf"
        public static let mime = "audio/x-caf"
        /// A recording shorter than this is treated as an accidental tap and discarded.
        public static let minDuration = 0.5
        /// Recording stops by itself at this length.
        public static let maxDuration = 600.0

        public enum Outcome: Equatable, Sendable { case send, tooShort }

        public static func outcome(duration: Double) -> Outcome { duration < minDuration ? .tooShort : .send }
        /// The length of a finished recording. `AVAudioRecorder.currentTime` is 0 once the recorder has
        /// stopped by itself at the limit, so the time measured from the start is used when it is longer.
        public static func finalDuration(recorderTime: Double, measured: Double) -> Double {
            clamped(max(recorderTime, measured))
        }

        public static func clamped(_ duration: Double) -> Double { min(max(duration, 0), maxDuration) }
    }
}
