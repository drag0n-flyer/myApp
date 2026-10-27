import AppCore
import SwiftUI

/// "Data for this call": Auto and the four modes, with estimates and limits.
struct DataModeSheet: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    private var options: [DataSheetOption] {
        DataModePolicy.sheetOptions(server: model.server, settings: model.settings.call,
                                    network: model.network, inCall: model.activeCall?.inCallPreference)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                        Button {
                            model.calls.setPreference(option.preference)
                            dismiss()
                        } label: {
                            OptionRow(option: option)
                        }
                        .disabled(!option.isEnabled)
                        .accessibilityIdentifier("datasheet.option.\(index)")
                    }
                } footer: {
                    Text("This call only · audio, both directions, estimated")
                }
            }
            .navigationTitle("Data for this call")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}

private struct OptionRow: View {
    let option: DataSheetOption

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(option.title).foregroundStyle(.primary)
                if let subtitle = option.subtitle ?? (option.preference == .auto ? String(localized: "Best your network allows") : nil) {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                if let note = option.note {
                    Text(note).font(.caption.weight(.medium)).foregroundStyle(.orange)
                }
            }
            Spacer()
            if option.isSelected {
                Image(systemName: "checkmark").foregroundStyle(.tint).fontWeight(.semibold)
            } else if let estimate = option.estimate {
                Text(estimate).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.trailing)
            }
        }
        .opacity(option.isEnabled ? 1 : 0.45)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(option.isSelected ? .isSelected : [])
    }
}
