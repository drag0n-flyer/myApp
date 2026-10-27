import CallCore
import AppCore
import SwiftUI
import UserNotifications

#if os(macOS)
enum SidebarItem: Hashable {
    case folder(String)
    case calls
    case contacts
}

/// Sidebar (folders, Calls, Contacts) · list · conversation.
struct MacRootView: View {
    @Bindable var model: AppModel
    @State private var sidebar: SidebarItem? = .folder(ChatFolder.all.id)
    @State private var selectedChatID: String?
    @State private var detailPath: [ChatRoute] = []
    @State private var missedOnly = false

    var body: some View {
        NavigationSplitView {
            List(selection: $sidebar) {
                Section("Chats") {
                    ForEach(model.folders) { folder in
                        Label(model.folderChipTitle(folder), systemImage: symbol(for: folder))
                            .tag(SidebarItem.folder(folder.id))
                            .accessibilityIdentifier(folder.kind == .all ? "mac.sidebar.chats" : "mac.sidebar.folder.\(folder.id)")
                    }
                }
                Label("Calls", systemImage: "phone")
                    .tag(SidebarItem.calls)
                    .accessibilityIdentifier("mac.sidebar.calls")
                Label("Contacts", systemImage: "person.2")
                    .tag(SidebarItem.contacts)
                    .accessibilityIdentifier("mac.sidebar.contacts")
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 200)
        } content: {
            content
                .navigationSplitViewColumnWidth(min: 280, ideal: 340)
        } detail: {
            NavigationStack(path: $detailPath) {
                detailRoot
                    .navigationDestination(for: ChatRoute.self) { route in
                        ChatDestination(route: route, model: model) { detailPath.append($0) }
                    }
            }
        }
        .onChange(of: sidebar) { _, item in
            if case .folder(let id) = item, let folder = model.folders.first(where: { $0.id == id }) {
                model.selectedFolder = folder
            }
        }
        .onChange(of: model.openChatID) { _, id in
            guard let id else { return }
            open(.chat(id: id))
            model.openChatID = nil
        }
        .overlay(alignment: .top) {
            if let caller = model.incomingCaller {
                IncomingCallBanner(name: caller.displayName,
                                   accept: { model.macRinger?.answerFromBanner() },
                                   decline: { model.macRinger?.declineFromBanner() })
                    .padding(.top, 10)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.default, value: model.incomingCaller)
        #if DEBUG
        .toolbar {
            ToolbarItem {
                Button("Simulate incoming call", systemImage: "phone.arrow.down.left") { model.simulateIncomingCall() }
                    .accessibilityIdentifier("mac.simulateIncoming")
            }
        }
        #endif
    }

    @ViewBuilder private var content: some View {
        switch sidebar {
        case .calls:
            CallHistoryContent(model: model, missedOnly: $missedOnly)
                .navigationTitle("Calls")
        case .contacts:
            ContactsContent(model: model) { contactID in
                sidebar = .folder(ChatFolder.all.id)
                open(.chat(id: model.openChat(with: contactID)))
            }
            .navigationTitle("Contacts")
        case .folder, nil:
            ChatListContent(model: model, selectedChatID: selectedChatID, open: open)
                .navigationTitle(model.selectedFolder.name)
                .searchable(text: $model.chatListQuery, placement: .toolbar)
        }
    }

    @ViewBuilder private var detailRoot: some View {
        if let id = selectedChatID {
            ChatView(chatID: id, model: model) { detailPath.append($0) }
                .id(id)
        } else {
            ContentUnavailableView("Select a chat", systemImage: "bubble.left.and.bubble.right")
        }
    }

    private func open(_ route: ChatRoute) {
        switch route {
        case .chat(let id):
            selectedChatID = id
            detailPath = []
        case .requests, .info:
            detailPath = [route]
        }
    }

    private func symbol(for folder: ChatFolder) -> String {
        switch folder.kind {
        case .all: "bubble.left.and.bubble.right"
        case .unread: "envelope.badge"
        case .custom: "folder"
        }
    }
}

/// Incoming call on the Mac (no CallKit on macOS): in-app banner plus a notification.
private struct IncomingCallBanner: View {
    let name: String
    let accept: () -> Void
    let decline: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: name, size: 36)
            VStack(alignment: .leading) {
                Text(name).font(.headline)
                Text("Incoming call").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Decline", role: .destructive, action: decline)
                .accessibilityIdentifier("mac.incoming.decline")
            Button("Accept", action: accept)
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .accessibilityIdentifier("mac.incoming.accept")
        }
        .padding(12)
        .frame(width: 380)
        .glassBackground(in: RoundedRectangle(cornerRadius: 18))
    }
}

extension AppModel {
    /// Sample build: a contact calls; strangers don't ring (they become a missed call in their request).
    func simulateIncomingCall() {
        guard let caller = contacts.first else { return }
        guard chatStore.incomingCall(from: caller, video: false) == .ring else { return }
        macRinger?.ring(callID: "sample-\(UUID().uuidString)", caller: caller, video: false)
    }
}

extension AppModel: MacRingHandling {
    func macRingSetCaller(_ caller: Contact?) { incomingCaller = caller }

    /// Signed in: the live engine owns the call. Sample data: the simulated engine plays it.
    func macRingAnswer(callID: String, caller: Contact?, video: Bool) {
        if let liveCalls { liveCalls.ringAnswer(callID: callID, video: video); return }
        guard let caller else { return }
        sampleCalls.simulateIncoming(from: caller, video: video)
        sampleCalls.answer(video: video)
    }

    func macRingDecline(callID: String) {
        if let liveCalls { liveCalls.ringDecline(callID: callID) } else { sampleCalls.decline() }
    }

    func macRingStatus(callID: String) async -> CallStatus? { await liveCalls?.ringStatus(callID: callID) }

    func macRingFinished(callID: String, kind: CallKitEndKind) { liveCalls?.ringFinished(callID: callID, kind: kind) }
}
#endif
