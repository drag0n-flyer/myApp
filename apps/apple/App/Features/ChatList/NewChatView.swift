import AppCore
import SwiftUI

/// Pick a contact to start a chat with.
struct NewChatView: View {
    let model: AppModel
    let start: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showingAdd = false

    var body: some View {
        NavigationStack {
            List(model.contacts.sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }) { person in
                Button { start(person.id) } label: {
                    HStack(spacing: 12) {
                        Avatar(name: person.displayName, size: 36)
                        VStack(alignment: .leading) {
                            Text(person.displayName).foregroundStyle(.primary)
                            Text("@\(person.username)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .overlay {
                if model.contacts.isEmpty {
                    ContentUnavailableView {
                        Label("No contacts yet", systemImage: "person.2")
                    } actions: {
                        Button("Add contact") { showingAdd = true }
                            .buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("newchat.add")
                    }
                }
            }
            .sheet(isPresented: $showingAdd) { AddContactView(model: model) }
            .navigationTitle("New chat")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}
