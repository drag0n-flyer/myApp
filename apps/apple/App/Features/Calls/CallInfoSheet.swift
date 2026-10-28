import AppCore
import CallCore
import SwiftUI

/// "Call info": connection, delay, sound lost, quality and data used, in plain words.
/// The text comes from CallCore's `CallInfoText`.
struct CallInfoSheet: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    private var rows: [(label: String, value: String)] {
        guard let call = model.activeCall else { return [] }
        return CallInfoText.rows(stats: model.callInfoStats, state: call)
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    LabeledContent {
                        Text(LocalizedStringKey(row.value))
                    } label: {
                        Text(LocalizedStringKey(row.label))
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(Text("\(String(localized: String.LocalizationValue(row.label))), \(String(localized: String.LocalizationValue(row.value)))"))
                    .accessibilityIdentifier("callinfo.row.\(index)")
                }
            }
            .navigationTitle("Call info")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
