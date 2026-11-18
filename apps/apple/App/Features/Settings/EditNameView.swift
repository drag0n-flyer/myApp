import AppCore
import SwiftUI

/// "Edit name": a field that starts with the stored name (empty when none is stored, never the placeholder "You"),
/// the length as "12/40" under it, Cancel and Save. Save is on only while `DisplayNameRule` accepts the text.
struct EditNameView: View {
    let current: String
    let onSave: (String) -> Void
    let onCancel: () -> Void
    @State private var text: String

    init(current: String, onSave: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.current = current
        self.onSave = onSave
        self.onCancel = onCancel
        _text = State(initialValue: current)
    }

    private var length: Int { DisplayNameRule.length(text) }
    private var accepted: String? { DisplayNameRule.clean(text) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $text)
                        .accessibilityIdentifier("profile.name.field")
                        .submitLabel(.done)
                        .onSubmit { if let accepted { onSave(accepted) } }
                } footer: {
                    Text(verbatim: "\(length)/\(DisplayNameRule.max)")
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .foregroundStyle(length > DisplayNameRule.max ? Color.red : Color.secondary)
                        .accessibilityIdentifier("profile.name.count")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Edit name")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                        .accessibilityIdentifier("profile.name.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { if let accepted { onSave(accepted) } }
                        .disabled(accepted == nil)
                        .accessibilityIdentifier("profile.name.save")
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 200)
        #endif
    }
}
