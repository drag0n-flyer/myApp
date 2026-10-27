import Foundation

public struct BenchResult: Codable, Equatable, Sendable {
    public var mode: String  // raw | vpio | rnnoise
    public var seconds: Double
    public var cpuSeconds: Double
    public var realTimeFactor: Double
    public var footprintBytesBefore: UInt64
    public var footprintBytesAfter: UInt64
    /// Set when the pass was refused or failed; the other numbers are then zero.
    public var error: String?

    public init(mode: String, seconds: Double, cpuSeconds: Double, realTimeFactor: Double,
                footprintBytesBefore: UInt64, footprintBytesAfter: UInt64, error: String? = nil) {
        self.error = error
        self.mode = mode
        self.seconds = seconds
        self.cpuSeconds = cpuSeconds
        self.realTimeFactor = realTimeFactor
        self.footprintBytesBefore = footprintBytesBefore
        self.footprintBytesAfter = footprintBytesAfter
    }

    /// realTimeFactor = cpuSeconds / seconds (below 1 means faster than real time).
    public init(mode: String, seconds: Double, cpuSeconds: Double,
                footprintBytesBefore: UInt64, footprintBytesAfter: UInt64, error: String? = nil) {
        self.init(mode: mode, seconds: seconds, cpuSeconds: cpuSeconds,
                  realTimeFactor: seconds > 0 ? cpuSeconds / seconds : 0,
                  footprintBytesBefore: footprintBytesBefore, footprintBytesAfter: footprintBytesAfter,
                  error: error)
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

/// Sample-rate rule for the bench. RNNoise only works at 48 kHz, and the bench refuses
/// (rather than resamples, which would pollute the CPU and quality measurement).
public enum BenchRate {
    public static let required = 48_000.0

    /// Returns an error message when the pass must be refused, nil when it may run.
    public static func refusal(mode: String, sampleRate: Double) -> String? {
        guard mode == "rnnoise", sampleRate != required else { return nil }
        return "RNNoise needs a 48000 Hz input but the input is \(Int(sampleRate)) Hz; pass refused"
    }
}
