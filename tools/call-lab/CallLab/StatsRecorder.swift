import CallLabKit
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Writes `stats.csv` (header = `StatsRow.csvHeader`) and `events.csv` into the run folder.
@MainActor
final class StatsRecorder {
    let runID: String
    let runDir: URL
    private let t0 = ContinuousClock.now
    private var events = EventLog()
    private var statsHandle: FileHandle?
    private(set) var firstAudioSeen = false

    init(runID: String) throws {
        self.runID = runID
        runDir = try RunExport.makeRunDir(runID: runID)
        let statsURL = runDir.appendingPathComponent("stats.csv")
        try (StatsRow.csvHeader + "\n").write(to: statsURL, atomically: true, encoding: .utf8)
        statsHandle = try FileHandle(forWritingTo: statsURL)
        statsHandle?.seekToEndOfFile()
        #if os(iOS)
        UIDevice.current.isBatteryMonitoringEnabled = true
        #endif
        flushEvents()
    }

    var nowMs: Int {
        let d = ContinuousClock.now - t0
        return Int(d.components.seconds) * 1000 + Int(d.components.attoseconds / 1_000_000_000_000_000)
    }

    func event(_ e: LabEvent, detail: String = "") {
        events.record(e, atMs: nowMs, detail: detail)
        flushEvents()
    }

    private func flushEvents() {
        try? events.csv.write(to: runDir.appendingPathComponent("events.csv"), atomically: true, encoding: .utf8)
    }

    /// Adds device state, writes one row, and records `first_audio_packet` once.
    func sample(_ stats: [LabStat], knobs: LabKnobs, preset: String) {
        var row = StatsMapper.row(from: stats, tMs: nowMs, runID: runID, preset: preset, knobs: knobs)
        row.thermal = ProcessInfo.processInfo.thermalState.rawValue
        #if os(iOS)
        let level = UIDevice.current.batteryLevel
        row.battery = level >= 0 ? Double(level) : nil
        #endif
        if !firstAudioSeen, StatsMapper.hasInboundAudio(stats) {
            firstAudioSeen = true
            event(.firstAudioPacket)
        }
        statsHandle?.write(Data((row.csvLine + "\n").utf8))
    }

    func close() {
        try? statsHandle?.close()
        statsHandle = nil
    }
}
