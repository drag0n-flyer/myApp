import AppCore
import SwiftUI

/// Add a contact by username.
struct AddContactView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var username = ""
    @State private var notFound = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Username", text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .accessibilityIdentifier("addcontact.field")
                        .onSubmit(add)
                } footer: {
                    if notFound {
                        Text("No one with that username on this server.").foregroundStyle(.red)
                    }
                }
                Section {
                    Button("Add", action: add)
                        .disabled(username.trimmingCharacters(in: .whitespaces).isEmpty)
                        .accessibilityIdentifier("addcontact.submit")
                }
                AddByLinkSection(model: model, finish: { dismiss() })
            }
            .navigationTitle("Add contact")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }

    private func add() {
        let name = username.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "@"))
        Task {
            if await model.addContact(username: name) != nil {
                dismiss()
            } else {
                notFound = true
            }
        }
    }
}
