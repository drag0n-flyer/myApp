import SwiftUI

/// Noise-suppression bench screen. Unverified until L3's app target includes it.
struct NSBenchView: View {
    @StateObject private var bench = NSBench()
    @State private var runID = ISO8601DateFormatter().string(from: Date())
        .replacingOccurrences(of: ":", with: "-")
    @State private var clipID = "clip-01"
    @State private var duration = 30.0

    var body: some View {
        Form {
            Section("Run") {
                TextField("Run ID", text: $runID)
                    .accessibilityLabel("Run ID")
                TextField("Clip ID", text: $clipID)
                    .accessibilityLabel("Clip ID")
                Stepper("Seconds per pass: \(Int(duration))", value: $duration, in: 5...600, step: 5)
                    .accessibilityLabel("Seconds per pass")
                Button("Record three passes") {
                    Task { await bench.run(runID: runID, clipID: clipID, duration: duration) }
                }
                .disabled(bench.running)
                .accessibilityLabel("Record raw, voice-processing and RNNoise passes")
                Text(bench.status)
            }
            Section("Results") {
                ForEach(Array(bench.results.enumerated()), id: \.offset) { _, r in
                    VStack(alignment: .leading) {
                        Text(r.mode).font(.headline)
                        if let e = r.error { Text(e).foregroundStyle(.red) }
                        Text("CPU \(r.cpuSeconds, specifier: "%.3f") s of \(r.seconds, specifier: "%.1f") s (RTF \(r.realTimeFactor, specifier: "%.4f"))")
                        Text("Memory \(r.footprintBytesBefore / 1_000_000) to \(r.footprintBytesAfter / 1_000_000) MB")
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .navigationTitle("Noise bench")
    }
}
