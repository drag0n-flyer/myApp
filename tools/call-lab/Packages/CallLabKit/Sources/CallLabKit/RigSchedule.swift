import Foundation

public struct RigSchedule {
    /// Drops the first `leadIn` seconds (silence before the stimulus starts) and keeps `duration`
    /// seconds after it. A recording shorter than that returns what exists.
    public static func trim(recorded: [Int16], sampleRate: Int, leadIn: Double, duration: Double) -> [Int16] {
        let start = max(0, Int((leadIn * Double(sampleRate)).rounded()))
        let length = max(0, Int((duration * Double(sampleRate)).rounded()))
        guard start < recorded.count else { return [] }
        let end = min(recorded.count, start + length)
        return Array(recorded[start..<end])
    }
}
