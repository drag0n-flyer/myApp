import AppCore
import SwiftUI

/// Chats archived on this device. A new message does not bring a chat back.
struct ArchiveView: View {
    @Bindable var model: AppModel
    let open: (ChatRoute) -> Void

    var body: some View {
        List {
            ForEach(model.archivedChats) { chat in
                Button { open(.chat(id: chat.id)) } label: {
                    ChatRowView(chat: chat, name: model.contact(id: chat.contactID)?.displayName ?? "")
                }
                .buttonStyle(.plain)
                .modifier(ChatRowActions(chat: chat, model: model, inArchive: true, pinLimit: {}))
                .accessibilityIdentifier("chatlist.row.\(chat.id)")
            }
        }
        .listStyle(.plain)
        .overlay {
            if model.archivedChats.isEmpty {
                ContentUnavailableView("No archived chats", systemImage: "archivebox")
            }
        }
        .navigationTitle("Archived")
        .accessibilityIdentifier("archive.list")
    }
}

/// The "Archived" row at the top of the chat list: the number of archived chats and their unread messages.
struct ArchivedRowView: View {
    let chats: [Chat]

    var body: some View {
        let unread = ChatListOrder.archivedUnread(chats)
        HStack {
            Label("Archived", systemImage: "archivebox")
                .font(.subheadline.weight(.semibold))
            Spacer(minLength: 6)
            if unread > 0 {
                Text("\(unread)")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .frame(minWidth: 20, minHeight: 20)
                    .background(Capsule().fill(.secondary))
                    .fixedSize()
            }
            Text("\(chats.count)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Archived"))
        .accessibilityValue(Text("\(chats.count)"))
    }
}

/// Swipe actions and the long-press menu of a chat row, shared by the chat list and the archive.
struct ChatRowActions: ViewModifier {
    let chat: Chat
    let model: AppModel
    let inArchive: Bool
    /// Called when a pin was refused because 5 chats are already pinned.
    let pinLimit: () -> Void

    func body(content: Content) -> some View {
        content
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                if !inArchive { pinButton }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                archiveButton
                if !inArchive { muteButton }
            }
            .contextMenu {
                if !inArchive { pinButton }
                muteButton
                archiveButton
                if chat.unreadCount > 0 { markReadButton }
            }
    }

    @ViewBuilder private var pinButton: some View {
        if chat.pinnedAt == nil {
            Button {
                if !model.setPinned(chat.id, true) { pinLimit() }
            } label: { Label("Pin", systemImage: "pin") }
                .tint(.orange)
                .accessibilityIdentifier("chatlist.action.pin")
        } else {
            Button { model.setPinned(chat.id, false) } label: { Label("Unpin", systemImage: "pin.slash") }
                .tint(.orange)
                .accessibilityIdentifier("chatlist.action.pin")
        }
    }

    @ViewBuilder private var muteButton: some View {
        if chat.muted {
            Button { model.setMuted(chat.id, false) } label: { Label("Unmute", systemImage: "bell") }
                .tint(.indigo)
                .accessibilityIdentifier("chatlist.action.mute")
        } else {
            Button { model.setMuted(chat.id, true) } label: { Label("Mute", systemImage: "bell.slash") }
                .tint(.indigo)
                .accessibilityIdentifier("chatlist.action.mute")
        }
    }

    @ViewBuilder private var archiveButton: some View {
        if chat.archived {
            Button { model.setArchived(chat.id, false) } label: { Label("Unarchive", systemImage: "tray.and.arrow.up") }
                .tint(.blue)
                .accessibilityIdentifier("chatlist.action.archive")
        } else {
            Button { model.setArchived(chat.id, true) } label: { Label("Archive", systemImage: "archivebox") }
                .tint(.blue)
                .accessibilityIdentifier("chatlist.action.archive")
        }
    }

    private var markReadButton: some View {
        Button { model.markRead(chatID: chat.id) } label: { Label("Mark as read", systemImage: "checkmark.message") }
            .accessibilityIdentifier("chatlist.action.markread")
    }
}
