import Foundation
import Testing
@testable import RNNoiseLab

struct BenchLayoutTests {
    private func tempDir() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("bench-\(UUID().uuidString)")
    }

    @Test func wavPathIsModeSlashClip() {
        let u = BenchLayout.wavURL(runDir: URL(fileURLWithPath: "/r/run1"), mode: "vpio", clipID: "clip-03")
        #expect(u.path == "/r/run1/vpio/clip-03.wav")
    }

    @Test func prepareCreatesOneFolderPerMode() throws {
        let d = tempDir()
        defer { try? FileManager.default.removeItem(at: d) }
        try BenchLayout.prepare(runDir: d)
        for m in ["raw", "vpio", "rnnoise"] {
            var isDir: ObjCBool = false
            #expect(FileManager.default.fileExists(atPath: d.appendingPathComponent(m).path, isDirectory: &isDir))
            #expect(isDir.boolValue)
        }
    }

    @Test func jsonRoundTripAndKeys() throws {
        let d = tempDir()
        defer { try? FileManager.default.removeItem(at: d) }
        try BenchLayout.prepare(runDir: d)
        let r = BenchResult(mode: "rnnoise", seconds: 10, cpuSeconds: 0.5,
                            footprintBytesBefore: 100, footprintBytesAfter: 200)
        #expect(r.realTimeFactor == 0.05)
        try BenchLayout.writeJSON([r], runDir: d)
        #expect(try BenchLayout.readJSON(runDir: d) == [r])
        let text = try String(contentsOf: d.appendingPathComponent("bench.json"), encoding: .utf8)
        for k in ["mode", "seconds", "cpuSeconds", "realTimeFactor", "footprintBytesBefore", "footprintBytesAfter"] {
            #expect(text.contains("\"\(k)\""))
        }
    }
}
