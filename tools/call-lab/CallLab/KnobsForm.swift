import CallLabKit
import SwiftUI

struct KnobsForm: View {
    @Binding var knobs: LabKnobs
    @State private var preset: LabPreset = .balanced
    @State private var capOn = false
    @State private var restartOn = false

    var body: some View {
        Section("Preset") {
            Picker("Preset", selection: $preset) {
                ForEach(LabPreset.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .accessibilityLabel("Preset")
            .onChange(of: preset) { _, p in
                // A preset replaces the audio and video knobs, not the per-run path and restart choices.
                var k = p.knobs
                k.path = knobs.path; k.iceRestartAfter = knobs.iceRestartAfter; k.senderMaxBitrate = knobs.senderMaxBitrate
                knobs = k
            }
        }
        Section("Audio") {
            Stepper("Opus bitrate: \(knobs.opusBitrate) bit/s", value: $knobs.opusBitrate, in: 6_000...64_000, step: 1_000)
                .accessibilityLabel("Opus bitrate")
            Picker("Packet time", selection: $knobs.ptime) {
                ForEach([10, 20, 40, 60, 120], id: \.self) { Text("\($0) ms").tag($0) }
            }
            .accessibilityLabel("Packet time")
            if knobs.ptime == 120 {
                Text("120 ms needs custom WebRTC build")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Toggle("DTX", isOn: $knobs.dtx).accessibilityLabel("Discontinuous transmission")
            Toggle("FEC", isOn: $knobs.fec).accessibilityLabel("Forward error correction")
            Picker("Max playback rate", selection: $knobs.maxPlaybackRate) {
                ForEach([8_000, 12_000, 16_000, 24_000, 48_000], id: \.self) { Text("\($0) Hz").tag($0) }
            }
            .accessibilityLabel("Max playback rate")
            Toggle("TWCC", isOn: $knobs.twcc).accessibilityLabel("Transport-wide congestion control")
            Toggle("Cap sender bitrate", isOn: $capOn)
                .accessibilityLabel("Cap sender bitrate")
                .onChange(of: capOn) { _, on in knobs.senderMaxBitrate = on ? (knobs.senderMaxBitrate ?? knobs.opusBitrate) : nil }
            if capOn {
                Stepper("Cap: \(knobs.senderMaxBitrate ?? 0) bit/s",
                        value: Binding(get: { knobs.senderMaxBitrate ?? knobs.opusBitrate },
                                       set: { knobs.senderMaxBitrate = $0 }),
                        in: 6_000...64_000, step: 1_000)
                    .accessibilityLabel("Sender bitrate cap")
            }
        }
        Section("Video") {
            Picker("Video", selection: $knobs.video) {
                Text("Off").tag(VideoRung?.none)
                ForEach(VideoRung.allCases, id: \.self) { Text($0.rawValue).tag(VideoRung?.some($0)) }
            }
            .accessibilityLabel("Video rung")
        }
        Section("Network") {
            Picker("Path", selection: $knobs.path) {
                Text("Direct").tag(PathPolicy.direct)
                Text("Relay").tag(PathPolicy.relay)
            }
            .pickerStyle(.segmented)
            .accessibilityLabel("Network path")
            Toggle("ICE restart", isOn: $restartOn)
                .accessibilityLabel("ICE restart")
                .onChange(of: restartOn) { _, on in knobs.iceRestartAfter = on ? (knobs.iceRestartAfter ?? 5) : nil }
            if restartOn {
                Stepper("After \(knobs.iceRestartAfter ?? 5) s disconnected",
                        value: Binding(get: { knobs.iceRestartAfter ?? 5 }, set: { knobs.iceRestartAfter = $0 }), in: 1...60)
                    .accessibilityLabel("Seconds disconnected before ICE restart")
            }
        }
    }
}
