import Foundation

public enum RunNaming {
    /// `yyyyMMdd-HHmmss-<preset>-<path>`.
    public static func runID(date: Date, preset: String, path: PathPolicy, timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = timeZone
        f.dateFormat = "yyyyMMdd-HHmmss"
        return "\(f.string(from: date))-\(preset)-\(path.rawValue)"
    }

    public static func zipName(runID: String) -> String { "\(runID).zip" }

    /// The preset whose audio/video knobs equal `k` (path, restart and sender cap are per-run choices
    /// and do not count), else "custom".
    public static func presetName(for k: LabKnobs) -> String {
        for p in LabPreset.allCases {
            var base = p.knobs
            base.path = k.path; base.iceRestartAfter = k.iceRestartAfter; base.senderMaxBitrate = k.senderMaxBitrate
            if base == k { return p.rawValue }
        }
        return "custom"
    }
}
