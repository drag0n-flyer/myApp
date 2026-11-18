import AppCore
import SwiftUI

/// Chat info for a group: photo, name, the people with their roles, and what each role may do. The server enforces
/// the same rules again, so a menu item that shows is a convenience, not the gate. It shows no encryption type
/// except the one line chat info always has, in the existing words.
struct GroupInfoView: View {
    let chatID: String
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmLeave = false
    @State private var confirmDelete = false
    @State private var pendingRemoval: GroupMemberRow?
    @State private var pendingOwner: GroupMemberRow?
    @State private var showingEdit = false
    @State private var showingAdd = false
    @State private var safetyChat: SafetyTarget?
    @State private var message: String?

    private var chat: Chat? { model.chat(id: chatID) }
    private var info: GroupInfo? { model.groupInfo(chatID: chatID) }

    var body: some View {
        List {
            if let info {
                header(info)
                if GroupScreenRules.canAdd(myRole: info.myRole, myState: info.myState) {
                    Section {
                        Button("Add members", systemImage: "person.badge.plus") { showingAdd = true }
                            .accessibilityIdentifier("groupinfo.add")
                    }
                }
                membersSection(info)
                settingsSection(info)
                if let chat { encryptionSection(chat) }
                leaveSection(info)
            }
        }
        .navigationTitle("Info")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .sheet(isPresented: $showingEdit) {
            if let info { GroupEditView(model: model, info: info) }
        }
        .sheet(isPresented: $showingAdd) {
            if let info { GroupAddMembersView(model: model, info: info) }
        }
        .sheet(item: $safetyChat) { target in
            NavigationStack {
                ChatInfoView(chatID: target.chatID, model: model)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { safetyChat = nil } } }
            }
        }
        .confirmationDialog("Leave this group?", isPresented: $confirmLeave, titleVisibility: .visible) {
            Button("Leave group", role: .destructive) { run { await model.leaveGroup(chatID: chatID) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You will stop getting its messages. Its history stays on this device until you delete the chat.")
        }
        .confirmationDialog("Delete this chat?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete chat", role: .destructive) {
                model.deleteChat(chatID: chatID)
                model.closeChatRequests += 1
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its messages and files are removed from this device.")
        }
        .confirmationDialog(
            pendingRemoval.map { String(localized: "Remove \(displayName($0)) from this group?") } ?? "",
            isPresented: Binding(get: { pendingRemoval != nil }, set: { if !$0 { pendingRemoval = nil } }),
            titleVisibility: .visible, presenting: pendingRemoval
        ) { member in
            Button("Remove from group", role: .destructive) { run { await model.removeMember(chatID: chatID, account: member.accountID) } }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog(
            pendingOwner.map { String(localized: "Make \(displayName($0)) the owner?") } ?? "",
            isPresented: Binding(get: { pendingOwner != nil }, set: { if !$0 { pendingOwner = nil } }),
            titleVisibility: .visible, presenting: pendingOwner
        ) { member in
            Button("Make owner") { run { await model.setRole(chatID: chatID, account: member.accountID, role: .owner) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("You will become an admin.")
        }
        .alert(message.map { LocalizedStringKey($0) } ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") {}
        }
    }

    // MARK: sections

    private func header(_ info: GroupInfo) -> some View {
        Section {
            VStack(spacing: 8) {
                GroupPhotoView(photo: info.photo, size: 84)
                Text(info.name ?? String(localized: "Group")).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                    .accessibilityIdentifier("groupinfo.name")
                Text("\(info.members.count) members").foregroundStyle(.secondary)
                    .accessibilityIdentifier("groupinfo.count")
                HStack(spacing: 12) {
                    actionButton("Search", "magnifyingglass") {
                        // Back to the chat, which opens its own search field.
                        model.chatSearchRequest = chatID
                        dismiss()
                    }
                    if GroupScreenRules.canEditInfo(myRole: info.myRole, myState: info.myState) {
                        actionButton("Edit", "pencil") { showingEdit = true }
                            .accessibilityIdentifier("groupinfo.edit")
                    }
                }
                .padding(.top, 6)
            }
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
        }
    }

    private func membersSection(_ info: GroupInfo) -> some View {
        Section {
            ForEach(GroupScreenRules.sorted(info.members), id: \.accountID) { member in
                memberRow(member, info: info)
            }
        } header: {
            Text("Members")
        }
    }

    private func settingsSection(_ info: GroupInfo) -> some View {
        Section {
            if let chat {
                NavigationLink {
                    MediaGalleryView(chatID: chatID, model: model)
                } label: {
                    LabeledContent("Media and files", value: "\(ChatMedia.count(chat.messages))")
                }
                .accessibilityIdentifier("chatinfo.media")
            }
            if GroupScreenRules.canSetTimer(myRole: info.myRole, myState: info.myState) {
                DisappearingMessagesRow(chatID: chatID, model: model)
            }
            if info.myState == .member {
                Toggle(isOn: Binding(get: { chat?.muted ?? false }, set: { model.setMuted(chatID, $0) })) {
                    Label("Mute", systemImage: "bell.slash")
                }
                .accessibilityIdentifier("groupinfo.mute")
            }
        }
    }

    private func encryptionSection(_ chat: Chat) -> some View {
        Section {
            Label(chat.encryption.infoText, systemImage: chat.encryption == .endToEnd ? "lock" : "server.rack")
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(chat.encryption.infoText))
                .accessibilityIdentifier("chatinfo.encryption")
        }
    }

    @ViewBuilder
    private func leaveSection(_ info: GroupInfo) -> some View {
        if GroupScreenRules.canLeave(myState: info.myState) {
            Section {
                Button("Leave group", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) { confirmLeave = true }
                    .accessibilityIdentifier("groupinfo.leave")
            }
        }
        if GroupScreenRules.canDeleteChat(myState: info.myState) {
            Section {
                Text("You're no longer in this group")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("groupinfo.gone")
                Button("Delete chat", systemImage: "trash", role: .destructive) { confirmDelete = true }
                    .accessibilityIdentifier("groupinfo.delete")
            }
        }
    }

    // MARK: members

    private func displayName(_ member: GroupMemberRow) -> String {
        if member.isMe { return String(localized: "You") }
        return member.name.isEmpty ? String(localized: "Someone") : member.name
    }

    private func roleText(_ role: GroupRole) -> String {
        switch role {
        case .owner: String(localized: "Owner")
        case .admin: String(localized: "Admin")
        case .member: String(localized: "Member")
        }
    }

    private func title(for action: GroupMemberAction) -> LocalizedStringKey {
        switch action {
        case .message: "Message"
        case .viewSafetyNumber: "View safety number"
        case .makeAdmin: "Make admin"
        case .removeAdmin: "Remove as admin"
        case .makeOwner: "Make owner"
        case .remove: "Remove from group"
        }
    }

    private func symbol(for action: GroupMemberAction) -> String {
        switch action {
        case .message: "bubble.left"
        case .viewSafetyNumber: "checkmark.shield"
        case .makeAdmin: "person.badge.shield.checkmark"
        case .removeAdmin: "person.badge.minus"
        case .makeOwner: "crown"
        case .remove: "person.badge.minus"
        }
    }

    private func perform(_ action: GroupMemberAction, on member: GroupMemberRow) {
        switch action {
        case .message:
            model.openChatID = model.openChat(with: member.accountID)
        case .viewSafetyNumber:
            safetyChat = SafetyTarget(chatID: model.openChat(with: member.accountID))
        case .makeAdmin:
            run { await model.setRole(chatID: chatID, account: member.accountID, role: .admin) }
        case .removeAdmin:
            run { await model.setRole(chatID: chatID, account: member.accountID, role: .member) }
        case .makeOwner:
            pendingOwner = member
        case .remove:
            pendingRemoval = member
        }
    }

    @ViewBuilder
    private func memberRow(_ member: GroupMemberRow, info: GroupInfo) -> some View {
        let actions = GroupScreenRules.actions(myRole: info.myRole, myState: info.myState, target: member)
        let name = displayName(member)
        let label = "\(name), \(roleText(member.role))"
        let content = HStack(spacing: 12) {
            Avatar(name: name, colorKey: member.accountID, size: 40)
            Text(name).foregroundStyle(.primary)
            Spacer(minLength: 8)
            if member.role != .member {
                Text(roleText(member.role)).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        if actions.isEmpty {
            content
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(label))
                .accessibilityIdentifier("groupinfo.member.\(member.accountID)")
        } else {
            Menu {
                ForEach(actions, id: \.self) { action in
                    Button(role: action == .remove ? .destructive : nil) { perform(action, on: member) } label: {
                        Label(title(for: action), systemImage: symbol(for: action))
                    }
                }
            } label: {
                content.contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(label))
            .accessibilityIdentifier("groupinfo.member.\(member.accountID)")
            .accessibilityActions {
                ForEach(actions, id: \.self) { action in
                    Button(title(for: action)) { perform(action, on: member) }
                }
            }
        }
    }

    private func actionButton(_ title: LocalizedStringKey, _ symbol: String, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.title3)
                Text(title).font(.caption)
            }
            .frame(minWidth: 64, minHeight: 52)
            .glassBackground(in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.tint)
    }

    /// Runs a change and says in plain words when it did not work.
    private func run(_ change: @escaping @MainActor () async -> GroupChangeOutcome) {
        Task {
            if let text = await change().message { message = text }
        }
    }
}

/// The one-to-one chat whose info page (with the safety number) is shown for a member.
private struct SafetyTarget: Identifiable {
    let chatID: String
    var id: String { chatID }
}

/// A group's photo, or three people on a neutral tile. The photo is drawn only when it passes the profile photo rule
/// (`ProfileImageCache` checks it again before any decode).
struct GroupPhotoView: View {
    let photo: Data?
    var size: CGFloat = 46

    var body: some View {
        if let photo = GroupScreenRules.displayablePhoto(photo), let image = ProfileImageCache.image(for: photo) {
            image
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(Circle())
                .accessibilityLabel(Text("Group photo"))
                .accessibilityIdentifier("groupinfo.photo")
        } else {
            Image(systemName: "person.3.fill")
                .font(.system(size: size * 0.38))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(Circle().fill(Color.neutralAvatarTile))
                .accessibilityHidden(true)
        }
    }
}
