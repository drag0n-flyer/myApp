import AppCore
import SwiftUI

struct DataStorageView: View {
    @Bindable var model: AppModel

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
}
