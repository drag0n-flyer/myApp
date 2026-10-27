import Foundation
import AppCore

/// The call info sheet's rows, in plain words. Codec and bitrate are never shown; they go only to the log.
public enum CallInfoText {
    public static func rows(stats: MediaStats, state: CallState) -> [(label: String, value: String)] {
        let delay = stats.roundTripTime.map { "\(Int(($0 * 1000).rounded())) ms" } ?? "—"
        let lost = "\(Int((stats.inboundLossFraction * 100).rounded()))%"
        return [
            ("Connection", stats.usingRelay ? "Through your server" : "Direct"),
            ("Delay", delay),
            ("Sound lost", lost),
            ("Quality", state.effectiveMode.title),
            ("Data used", DataFormat.size(bytes: state.bytesUsed)),
        ]
    }
}
