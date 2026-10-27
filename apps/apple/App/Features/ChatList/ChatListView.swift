import AppCore
import SwiftUI

enum ChatRoute: Hashable {
    case chat(id: String)
    case requests
    case info(chatID: String)
}

#if os(iOS)
/// The Chats tab on iPhone.
struct ChatListView: View {
    @Bindable var model: AppModel
    let showsSearchField: Bool
    @State private var path: [ChatRoute] = []
    @State private var showingNewChat = false

    var body: some View {
        NavigationStack(path: $path) {
            ChatListContent(model: model) { path.append($0) }
                .navigationTitle("Chats")
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Menu {
                            Button("Chat folders", systemImage: "folder") { model.selectedTab = .settings }
                            Button("Mark all as read", systemImage: "checkmark.message") {
                                model.chats(in: .unread).forEach { model.markRead(chatID: $0.id) }
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                        }
                        .accessibilityLabel("More")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { showingNewChat = true } label: { Image(systemName: "square.and.pencil") }
                            .accessibilityLabel("New chat")
                            .accessibilityIdentifier("chatlist.compose")
                    }
                }
                .modifier(OptionalSearch(isOn: showsSearchField, text: $model.searchText))
                .navigationDestination(for: ChatRoute.self) { route in
                    ChatDestination(route: route, model: model) { path.append($0) }
                }
        }
        .sheet(isPresented: $showingNewChat) {
            NewChatView(model: model) { contactID in
                showingNewChat = false
                path = [.chat(id: model.openChat(with: contactID))]
            }
        }
        .onChange(of: model.openChatID) { _, id in
            guard let id else { return }
            path = [.chat(id: id)]
            model.openChatID = nil
        }
    }
}

/// The iOS 26 search tab: chats, messages, and contacts.
struct SearchView: View {
    @Bindable var model: AppModel
    @State private var path: [ChatRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                let chats = model.searchText.isEmpty ? [] : model.chats(in: .all)
                ForEach(chats) { chat in
                    Button { path.append(.chat(id: chat.id)) } label: {
                        ChatRowView(chat: chat, name: model.contact(id: chat.contactID)?.displayName ?? "")
                    }
                    .buttonStyle(.plain)
                }
                let people = model.contacts.filter { !model.searchText.isEmpty && $0.displayName.localizedCaseInsensitiveContains(model.searchText) }
                if !people.isEmpty {
                    Section("Contacts") {
                        ForEach(people) { person in
                            Button { path.append(.chat(id: model.openChat(with: person.id))) } label: {
                                Label { Text(person.displayName) } icon: { Avatar(name: person.displayName, size: 32) }
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Search")
            .searchable(text: $model.searchText, prompt: "Chats, messages, contacts")
            .navigationDestination(for: ChatRoute.self) { route in
                ChatDestination(route: route, model: model) { path.append($0) }
            }
        }
    }
}

private struct OptionalSearch: ViewModifier {
    let isOn: Bool
    @Binding var text: String

    func body(content: Content) -> some View {
        if isOn {
            content.searchable(text: $text, prompt: "Search")
        } else {
            content
        }
    }
}
#endif

/// Screens reachable from the chat list.
struct ChatDestination: View {
    let route: ChatRoute
    let model: AppModel
    let open: (ChatRoute) -> Void

    var body: some View {
        switch route {
        case .chat(let id): ChatView(chatID: id, model: model, open: open)
        case .requests: MessageRequestsView(model: model, open: open)
        case .info(let chatID): ChatInfoView(chatID: chatID, model: model)
        }
    }
}

/// Folder chips, status strip, message requests row, and chats. Shared by iPhone and Mac.
struct ChatListContent: View {
    @Bindable var model: AppModel
    var selectedChatID: String?
    let open: (ChatRoute) -> Void

    var body: some View {
        List {
            VStack(alignment: .leading, spacing: 8) {
                FolderChipsView(model: model)
                StatusStripView(host: model.server.host, modeLabel: model.stripLabel)
            }
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 12))

            if let title = model.messageRequestsRowTitle {
                Button { open(.requests) } label: {
                    Label(title, systemImage: "tray")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.tint)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("chatlist.requests")
            }

            ForEach(model.chats(in: model.selectedFolder)) { chat in
                Button { open(.chat(id: chat.id)) } label: {
                    ChatRowView(chat: chat, name: model.contact(id: chat.contactID)?.displayName ?? "")
                }
                .buttonStyle(.plain)
                .listRowBackground(selectedChatID == chat.id ? Color.accentColor.opacity(0.15) : nil)
                .accessibilityIdentifier("chatlist.row.\(chat.id)")
            }
        }
        .listStyle(.plain)
    }
}
