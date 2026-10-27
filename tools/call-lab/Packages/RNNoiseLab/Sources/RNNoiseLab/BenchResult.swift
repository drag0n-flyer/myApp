import Foundation

public struct BenchResult: Codable, Equatable, Sendable {
    public var mode: String  // raw | vpio | rnnoise
    public var seconds: Double
    public var cpuSeconds: Double
    public var realTimeFactor: Double
    public var footprintBytesBefore: UInt64
    public var footprintBytesAfter: UInt64

    public init(mode: String, seconds: Double, cpuSeconds: Double, realTimeFactor: Double,
                footprintBytesBefore: UInt64, footprintBytesAfter: UInt64) {
        self.mode = mode
        self.seconds = seconds
        self.cpuSeconds = cpuSeconds
        self.realTimeFactor = realTimeFactor
        self.footprintBytesBefore = footprintBytesBefore
        self.footprintBytesAfter = footprintBytesAfter
    }

    /// realTimeFactor = cpuSeconds / seconds (below 1 means faster than real time).
    public init(mode: String, seconds: Double, cpuSeconds: Double,
                footprintBytesBefore: UInt64, footprintBytesAfter: UInt64) {
        self.init(mode: mode, seconds: seconds, cpuSeconds: cpuSeconds,
                  realTimeFactor: seconds > 0 ? cpuSeconds / seconds : 0,
                  footprintBytesBefore: footprintBytesBefore, footprintBytesAfter: footprintBytesAfter)
    }
}

/// Output layout of a bench run, one subfolder per pass:
/// `<runDir>/<mode>/<clip-id>.wav` and `<runDir>/bench.json`.
/// The measurement protocol (docs/measurements/protocol-noise.md) depends on this layout.
public enum BenchLayout {
    public static let modes = ["raw", "vpio", "rnnoise"]
    public static let jsonName = "bench.json"

    public static func wavURL(runDir: URL, mode: String, clipID: String) -> URL {
        runDir.appendingPathComponent(mode, isDirectory: true)
            .appendingPathComponent("\(clipID).wav")
    }

    /// Creates `<runDir>/<mode>` for every mode.
    public static func prepare(runDir: URL) throws {
        for m in modes {
            try FileManager.default.createDirectory(
                at: runDir.appendingPathComponent(m, isDirectory: true),
                withIntermediateDirectories: true)
        }
    }

    public static func writeJSON(_ results: [BenchResult], runDir: URL) throws {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        try enc.encode(results).write(to: runDir.appendingPathComponent(jsonName), options: .atomic)
    }

    public static func readJSON(runDir: URL) throws -> [BenchResult] {
        try JSONDecoder().decode([BenchResult].self,
                                 from: Data(contentsOf: runDir.appendingPathComponent(jsonName)))
    }
}
