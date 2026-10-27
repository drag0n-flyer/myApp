import AppCore
import SwiftUI

/// First messages from people who aren't contacts.
struct MessageRequestsView: View {
    @Bindable var model: AppModel
    let open: (ChatRoute) -> Void

    var body: some View {
        List {
            Section {
                ForEach(model.messageRequests) { chat in
                    Button { open(.chat(id: chat.id)) } label: {
                        ChatRowView(chat: chat, name: model.contact(id: chat.contactID)?.displayName ?? "")
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("requests.row.\(chat.id)")
                }
            } footer: {
                Text("People not in your contacts land here. They can't ring you until you accept.")
            }
        }
        .overlay {
            if model.messageRequests.isEmpty {
                ContentUnavailableView("No message requests", systemImage: "tray")
            }
        }
        .navigationTitle("Message requests")
    }
}
