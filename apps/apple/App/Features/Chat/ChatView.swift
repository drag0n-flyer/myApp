import AppCore
import SwiftUI

/// A conversation. Request chats show Accept / Block / Report instead of the composer.
struct ChatView: View {
    let chatID: String
    @Bindable var model: AppModel
    let open: (ChatRoute) -> Void
    @State private var draft = ""
    @State private var downloaded: Set<String> = []

    private var chat: Chat? { model.chat(id: chatID) }
    private var contact: Contact? { chat.flatMap { model.contact(id: $0.contactID) } }

    var body: some View {
        Group {
            if let chat {
                messages(chat)
                    .safeAreaInset(edge: .bottom) {
                        if chat.isRequest {
                            RequestActionsBar(chat: chat, model: model)
                        } else {
                            ComposerView(text: $draft, send: send, sendSample: sendSample,
                                         sendAttachment: { model.sendAttachment($0, in: chatID) })
                        }
                    }
            } else {
                ContentUnavailableView("This chat was removed", systemImage: "bubble.left.and.exclamationmark.bubble.right")
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                ChatHeaderView(name: contact?.displayName ?? "", status: String(localized: "online")) {
                    open(.info(chatID: chatID))
                }
            }
            if chat?.isRequest == false, let contactID = chat?.contactID {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button { model.startCall(contactID: contactID, video: false) } label: { Image(systemName: "phone") }
                        .accessibilityLabel("Audio call")
                        .accessibilityIdentifier("chat.call.audio")
                    Button { model.startCall(contactID: contactID, video: true) } label: { Image(systemName: "video") }
                        .accessibilityLabel("Video call")
                        .accessibilityIdentifier("chat.call.video")
                }
            }
        }
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        #endif
        .onAppear { model.markRead(chatID: chatID) }
    }

    private func messages(_ chat: Chat) -> some View {
        ScrollView {
            LazyVStack(spacing: 6) {
                ForEach(chat.messages) { message in
                    MessageBubbleView(
                        message: message,
                        isMine: message.senderID == model.chatStore.me.id,
                        reply: message.replyToID.flatMap { id in chat.messages.first { $0.id == id } },
                        replySenderName: { id in id == model.chatStore.me.id ? String(localized: "You") : model.contact(id: id)?.displayName ?? "" },
                        showsMedia: downloaded.contains(message.id) || autoDownloads(message),
                        download: { downloaded.insert(message.id) }
                    )
                    .id(message.id)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
        }
        .defaultScrollAnchor(.bottom)
        .background(Color.groupedBackground)
    }

    private func autoDownloads(_ message: Message) -> Bool {
        let kind: MediaKind
        switch message.content {
        case .photo: kind = .photo
        case .file: kind = .file
        case .voice: kind = .voice
        case .text, .call, .deleted, .unsupported, .system: return true
        }
        return AutoDownloadPolicy.shouldDownload(kind, network: model.network, settings: model.settings.autoDownload)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        model.send(.text(text), in: chatID)
        draft = ""
    }

    private func sendSample(_ content: MessageContent) {
        model.send(content, in: chatID)
    }
}

/// Accept, Block, or Report a message request.
struct RequestActionsBar: View {
    let chat: Chat
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 10) {
            Text("This person isn't in your contacts. Their calls won't ring until you accept.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button(role: .destructive) {
                    model.block(contactID: chat.contactID)
                    dismiss()
                } label: { Text("Block").frame(maxWidth: .infinity) }
                    .accessibilityIdentifier("requests.block")
                Button {
                    model.report(chatID: chat.id)
                } label: { Text("Report").frame(maxWidth: .infinity) }
                    .accessibilityIdentifier("requests.report")
                Button {
                    model.accept(requestID: chat.id)
                } label: { Text("Accept").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("requests.accept")
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .padding()
        .glassBackground(in: RoundedRectangle(cornerRadius: 24))
        .padding(.horizontal, 10)
        .padding(.bottom, 6)
    }
}
