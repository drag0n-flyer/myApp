import AppCore
import SwiftUI

struct DataStorageView: View {
    @Bindable var model: AppModel

    // Sample numbers until real counters exist.
    private let callsMB = 42.0, mediaMB = 28.0, messagesMB = 12.0

    var body: some View {
        Form {
            Section {
                Picker("Call data mode", selection: $model.settings.call.callDataMode) {
                    Text(ModePreference.auto.title).tag(ModePreference.auto)
                    ForEach(DataMode.allCases, id: \.self) { Text($0.title).tag(ModePreference.fixed($0)) }
                }
                .accessibilityIdentifier("data.callmode")
                Picker("Limit on mobile data", selection: $model.settings.call.mobileDataLimit) {
                    ForEach(DataMode.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .accessibilityIdentifier("data.mobilelimit")
                Toggle("Start video calls with video off on mobile", isOn: $model.settings.call.videoOffOnMobile)
                    .accessibilityIdentifier("data.videooff")
            } header: {
                Text("Calls")
            } footer: {
                Text("Server default: \(model.server.defaultPreference.title) · Server maximum: \(model.server.maximum.title)")
                    .accessibilityIdentifier("data.footer")
            }

            Section("Auto-download media") {
                scopePicker("On Wi-Fi", selection: $model.settings.autoDownload.onWiFi, id: "data.wifi")
                scopePicker("On mobile data", selection: $model.settings.autoDownload.onMobile, id: "data.mobile")
                Toggle("Voice messages always", isOn: $model.settings.autoDownload.voiceAlways)
                    .accessibilityIdentifier("data.voice")
            }

            Section("This month") {
                VStack(alignment: .leading, spacing: 6) {
                    LabeledContent("Mobile data used", value: "\(Int(callsMB + mediaMB + messagesMB)) MB")
                    UsageBar(parts: [(callsMB, .green), (mediaMB, .blue), (messagesMB, .orange)])
                    HStack(spacing: 10) {
                        legend("Calls", callsMB, .green)
                        legend("Media", mediaMB, .blue)
                        legend("Messages", messagesMB, .orange)
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                LabeledContent("Storage on this device", value: "312 MB")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Data & Storage")
    }

    private func scopePicker(_ title: LocalizedStringKey, selection: Binding<DownloadScope>, id: String) -> some View {
        Picker(title, selection: selection) {
            ForEach(DownloadScope.allCases, id: \.self) { Text($0.title).tag($0) }
        }
        .accessibilityIdentifier(id)
    }

    private func legend(_ title: LocalizedStringKey, _ mb: Double, _ color: Color) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(title)
            Text("\(Int(mb)) MB")
        }
    }
}

private struct UsageBar: View {
    let parts: [(Double, Color)]

    var body: some View {
        GeometryReader { geo in
            let total = max(parts.reduce(0) { $0 + $1.0 } / 0.82, 1)
            HStack(spacing: 0) {
                ForEach(parts.indices, id: \.self) { i in
                    parts[i].1.frame(width: geo.size.width * parts[i].0 / total)
                }
                Spacer(minLength: 0)
            }
        }
        .frame(height: 8)
        .background(Color.secondary.opacity(0.2))
        .clipShape(Capsule())
        .accessibilityHidden(true)
    }
}
