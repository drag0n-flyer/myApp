import AppCore
import SwiftUI

/// Pick a contact to start a chat with.
struct NewChatView: View {
    let model: AppModel
    /// A link taken in Add contact; the presenter closes this sheet too, then calls `model.handle(url:)`.
    let onLink: (URL) -> Void
    let start: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showingAdd = false
    @State private var linkFromAdd: URL?
    @State private var showingNewGroup = false

    var body: some View {
        NavigationStack {
            List {
                Button { showingNewGroup = true } label: {
                    Label("New group", systemImage: "person.3")
                }
                .accessibilityIdentifier("newchat.newgroup")
                ForEach(model.contacts.sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }) { person in
                    Button { start(person.id) } label: {
                        HStack(spacing: 12) {
                            Avatar(name: person.displayName, colorKey: person.id, size: 36)
                            VStack(alignment: .leading) {
                                Text(person.displayName).foregroundStyle(.primary)
                                Text("@\(person.username)").font(.caption).foregroundStyle(.secondary)
                            }
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
            .sheet(isPresented: $showingAdd, onDismiss: {
                if let url = linkFromAdd { linkFromAdd = nil; onLink(url) }
            }) {
                AddContactView(model: model, onLink: { linkFromAdd = $0 })
            }
            .sheet(isPresented: $showingNewGroup) {
                // A created group opens like a chat made from a contact: `start` takes the chat ID, which for a group is its own.
                NewGroupView(model: model) { chatID in
                    showingNewGroup = false
                    start(chatID)
                }
            }
            .navigationTitle("New chat")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { showingAdd = true } label: { Label("Add contact", systemImage: "person.badge.plus") }
                        .accessibilityIdentifier("newchat.addtoolbar")
                }
            }
        }
    }
}
