import AppCore
import SwiftUI

enum ChatRoute: Hashable {
    case chat(id: String)
    case requests
    case archive
    case info(chatID: String)
}

#if os(iOS)
/// The Chats tab. Compact width (iPhone, iPad in a narrow split): list, then chat, in one stack. Regular width (iPad,
/// a wide window): the list beside the chat (`NavigationSplitView`).
///
/// One `path` serves both layouts so a rotation or a split-view resize loses nothing: its first element is the screen
/// beside the list (a chat, the requests or the archive) and the rest are pushed on top of it; compact width shows the
/// same list as a stack. The composer's unsent text is kept by the store (drafts), not by this view.
struct ChatListView: View {
    @Bindable var model: AppModel
    let showsSearchField: Bool
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var path: [ChatRoute] = []
    @State private var showingNewChat = false
    @State private var linkFromNewChat: URL?

    var body: some View {
        // Switching layouts rebuilds the views below, so `path` is the only state carried over (the chat's scroll
        // position is not).
        Group {
            if sizeClass == .regular { splitLayout } else { stackLayout }
        }
        .sheet(isPresented: $showingNewChat, onDismiss: {
            if let url = linkFromNewChat { linkFromNewChat = nil; model.handle(url: url) }
        }) {
            NewChatView(model: model, onLink: { url in linkFromNewChat = url; showingNewChat = false }) { contactID in
                showingNewChat = false
                path = [.chat(id: model.openChat(with: contactID))]
            }
        }
        .onChange(of: model.openChatID) { _, id in
            guard let id else { return }
            path = [.chat(id: id)]
            model.openChatID = nil
        }
        .onAppear {
            // A chat held while the app was locked is opened once, after unlock (the view did not exist before).
            guard let id = model.openChatID else { return }
            path = [.chat(id: id)]
            model.openChatID = nil
        }
    }

    // MARK: Compact

    private var stackLayout: some View {
        NavigationStack(path: $path) {
            listColumn(open: { path.append($0) })
                .navigationDestination(for: ChatRoute.self) { route in
                    ChatDestination(route: route, model: model) { path.append($0) }
                }
        }
    }

    // MARK: Regular

    private var splitLayout: some View {
        NavigationSplitView {
            listColumn(selectedChatID: selectedChatID) { route in
                switch route {
                case .chat, .requests, .archive: path = [route]
                case .info: path.append(route)
                }
            }
        } detail: {
            NavigationStack(path: pushedRoutes) {
                detailRoot
                    .navigationDestination(for: ChatRoute.self) { route in
                        ChatDestination(route: route, model: model) { path.append($0) }
                    }
            }
        }
    }

    /// The chat shown beside the list (highlighted in it).
    private var selectedChatID: String? {
        if case .chat(let id)? = path.first { return id }
        return nil
    }

    /// What sits on top of the detail's first screen.
    private var pushedRoutes: Binding<[ChatRoute]> {
        Binding(
            get: { Array(path.dropFirst()) },
            set: { path = Array(path.prefix(1)) + $0 }
        )
    }

    @ViewBuilder private var detailRoot: some View {
        if let first = path.first {
            ChatDestination(route: first, model: model) { path.append($0) }
                .id(first)
        } else {
            ContentUnavailableView("Select a chat", systemImage: "bubble.left.and.bubble.right")
                .accessibilityIdentifier("chatlist.detail.empty")
        }
    }

    // MARK: List

    private func listColumn(selectedChatID: String? = nil, open: @escaping (ChatRoute) -> Void) -> some View {
        ChatListContent(model: model, selectedChatID: selectedChatID, newChat: { showingNewChat = true }, open: open)
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
            .modifier(OptionalSearch(isOn: showsSearchField, text: $model.chatListQuery))
    }
}

/// The iOS 26 search tab: chats, messages, and contacts.
struct SearchView: View {
    @Bindable var model: AppModel
    @State private var path: [ChatRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                let chats = model.searchText.isEmpty ? [] : model.chats(in: .all, matching: model.searchText)
                ForEach(chats) { chat in
                    Button { path.append(.chat(id: chat.id)) } label: {
                        ChatRowView(chat: chat, name: model.chatName(chat), isTyping: model.isTyping(chatID: chat.id))
                    }
                    .buttonStyle(.plain)
                }
                let people = model.contacts.filter { !model.searchText.isEmpty && $0.displayName.localizedCaseInsensitiveContains(model.searchText) }
                if !people.isEmpty {
                    Section("Contacts") {
                        ForEach(people) { person in
                            Button { path.append(.chat(id: model.openChat(with: person.id))) } label: {
                                Label { Text(person.displayName) } icon: { Avatar(name: person.displayName, colorKey: person.id, size: 32) }
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
        case .archive: ArchiveView(model: model, open: open)
        case .info(let chatID): ChatInfoView(chatID: chatID, model: model)
        }
    }
}

/// Folder chips, status strip, message requests row, and chats. Shared by iPhone and Mac.
struct ChatListContent: View {
    @Bindable var model: AppModel
    var selectedChatID: String?
    /// The "New chat" button of the empty list.
    var newChat: (() -> Void)?
    let open: (ChatRoute) -> Void
    @State private var showingPinLimit = false

    var body: some View {
        List {
            VStack(alignment: .leading, spacing: 8) {
                FolderChipsView(model: model)
                StatusStripView(host: model.server.host, modeLabel: model.stripLabel, status: model.connectionStatus)
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

            if !model.archivedChats.isEmpty {
                Button { open(.archive) } label: { ArchivedRowView(chats: model.archivedChats) }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("chatlist.archived")
            }

            if model.chats(in: .all).isEmpty, model.archivedChats.isEmpty, model.chatListQuery.isEmpty {
                ContentUnavailableView {
                    Label("No chats yet", systemImage: "bubble.left.and.bubble.right")
                } actions: {
                    if let newChat {
                        Button("New chat", action: newChat)
                            .buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("chatlist.empty.new")
                    }
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
                .accessibilityIdentifier("chatlist.empty")
            }

            ForEach(model.chats(in: model.selectedFolder, matching: model.chatListQuery)) { chat in
                Button { open(.chat(id: chat.id)) } label: {
                    ChatRowView(chat: chat, name: model.chatName(chat), isTyping: model.isTyping(chatID: chat.id))
                }
                .buttonStyle(.plain)
                .modifier(ChatRowActions(chat: chat, model: model, inArchive: chat.archived, pinLimit: { showingPinLimit = true }))
                .listRowBackground(selectedChatID == chat.id ? Color.accentColor.opacity(0.15) : nil)
                .accessibilityIdentifier("chatlist.row.\(chat.id)")
            }
        }
        .listStyle(.plain)
        .alert("You can pin up to 5 chats.", isPresented: $showingPinLimit) {
            Button("OK", role: .cancel) {}
        }
    }
}
