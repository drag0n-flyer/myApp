#if os(macOS)
import CallLabKit
import SwiftUI
import UniformTypeIdentifiers

// UNVERIFIED: never compiled.

struct RigView: View {
    @StateObject private var rig = Rig()
    @State private var inputs: [AudioDevice] = []
    @State private var outputs: [AudioDevice] = []
    @State private var input: AudioDevice?
    @State private var output: AudioDevice?
    @State private var stimulus: URL?
    @State private var runID = RunNaming.runID(date: Date(), preset: "rig", path: .direct)
    @State private var picking = false

    private var isRunning: Bool { if case .running = rig.state { return true } else { return false } }

    var body: some View {
        Form {
            Picker("Output device", selection: $output) {
                ForEach(outputs) { Text($0.name).tag(Optional($0)) }
            }
            Picker("Input device", selection: $input) {
                ForEach(inputs) { Text($0.name).tag(Optional($0)) }
            }
            TextField("Run ID", text: $runID)
            LabeledContent("Stimulus") {
                HStack {
                    Text(stimulus?.lastPathComponent ?? "None chosen")
                    Button("Choose stimulus file") { picking = true }
                }
            }
            HStack {
                Button("Play and record") {
                    guard let stimulus, let input, let output else { return }
                    let access = stimulus.startAccessingSecurityScopedResource()
                    Task {
                        await rig.run(stimulus: stimulus, input: input, output: output, runID: runID)
                        if access { stimulus.stopAccessingSecurityScopedResource() }
                    }
                }
                .disabled(isRunning || stimulus == nil || input == nil || output == nil)
                Button("Stop") { rig.stop() }.disabled(!isRunning)
            }
            Text(statusText).foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .frame(minWidth: 460, minHeight: 360)
        .navigationTitle("Rig")
        .fileImporter(isPresented: $picking, allowedContentTypes: [.wav]) { result in
            if case .success(let url) = result { stimulus = url }
        }
        .onAppear {
            inputs = AudioDevices.list(input: true)
            outputs = AudioDevices.list(input: false)
            input = input ?? inputs.first
            output = output ?? outputs.first
        }
    }

    private var statusText: String {
        switch rig.state {
        case .idle: return "Ready. Recording starts 2 seconds before the stimulus."
        case .running(let s): return s
        case .done(let s): return s
        case .failed(let s): return "Failed: \(s)"
        case .stopped: return "Stopped. Nothing was saved."
        }
    }
}
#endif
