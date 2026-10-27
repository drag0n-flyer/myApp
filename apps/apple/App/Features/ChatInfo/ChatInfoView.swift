import AppCore
import SwiftUI

/// The only place that shows a chat's encryption type (UI design §5).
struct ChatInfoView: View {
    let chatID: String
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var muted = false
    @State private var showingVerify = false
    @State private var confirmBlock = false
    @State private var confirmReport = false

    private var chat: Chat? { model.chat(id: chatID) }
    private var contact: Contact? { chat.flatMap { model.contact(id: $0.contactID) } }

    var body: some View {
        List {
            if let chat, let contact {
                Section {
                    VStack(spacing: 8) {
                        Avatar(name: contact.displayName, size: 84)
                        Text(contact.displayName).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                        Text("@\(contact.username)").foregroundStyle(.secondary)
                        HStack(spacing: 12) {
                            action("Audio", "phone") { model.startCall(contactID: contact.id, video: false) }
                            action("Video", "video") { model.startCall(contactID: contact.id, video: true) }
                            action(muted ? "Unmute" : "Mute", muted ? "bell.slash" : "bell") { muted.toggle() }
                            action("Search", "magnifyingglass") { model.searchChats(for: contact.displayName) }
                        }
                        .padding(.top, 6)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }

                Section {
                    LabeledContent("Media, files and links", value: "\(mediaCount(chat))")
                }

                Section {
                    if chat.modeMismatch {
                        // Sending is paused; this replaces the encryption line (Messaging Design 18).
                        Label("The server's settings for this chat don't match the ones it started with. Sending is paused to keep your messages private.",
                              systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("chatinfo.mismatch")
                    } else {
                        Label(chat.encryption.infoText, systemImage: chat.encryption == .endToEnd ? "lock" : "server.rack")
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(Text(chat.encryption.infoText))
                            .accessibilityIdentifier("chatinfo.encryption")
                        if model.safetyNumber(for: chatID)?.isVerified == true {
                            Label("Verified", systemImage: "checkmark.seal")
                                .accessibilityIdentifier("chatinfo.verified")
                        }
                    }
                    Button("Verify", systemImage: "checkmark.shield") { showingVerify = true }
                }

                Section {
                    LabeledContent("Disappearing messages", value: String(localized: "Off"))
                }

                Section {
                    Button("Report", systemImage: "exclamationmark.bubble", role: .destructive) { confirmReport = true }
                    Button("Block \(contact.displayName)", systemImage: "hand.raised", role: .destructive) { confirmBlock = true }
                }
            }
        }
        .navigationTitle("Info")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .sheet(isPresented: $showingVerify) {
            VerifyView(model: model, chatID: chatID, name: contact?.displayName ?? "")
        }
        .confirmationDialog("Report this chat?", isPresented: $confirmReport, titleVisibility: .visible) {
            Button("Report", role: .destructive) { model.report(chatID: chatID) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The last 10 messages will be sent to the server admin.")
        }
        .confirmationDialog("Block this person?", isPresented: $confirmBlock, titleVisibility: .visible) {
            Button("Block", role: .destructive) {
                if let id = chat?.contactID { model.block(contactID: id) }
                dismiss()
            }
        }
    }

    private func action(_ title: LocalizedStringKey, _ symbol: String, perform: @escaping () -> Void) -> some View {
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

    private func mediaCount(_ chat: Chat) -> Int {
        chat.messages.filter {
            switch $0.content {
            case .photo, .file: true
            default: false
            }
        }.count
    }
}

/// Safety number to compare in person or over a call (Messaging Design 20). It is the same on both phones.
private struct VerifyView: View {
    let model: AppModel
    let chatID: String
    let name: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if let number = model.safetyNumber(for: chatID) {
                    Text("Compare these numbers with \(name). If they match, your chat is safe.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 12) {
                        ForEach(Array(number.groups.enumerated()), id: \.offset) { index, group in
                            Text(group)
                                .font(.title3.monospacedDigit())
                                .accessibilityIdentifier("verify.group.\(index)")
                        }
                    }
                    .padding()
                    .background(Color.secondaryBackground, in: RoundedRectangle(cornerRadius: 16))
                    Toggle("Marked as verified", isOn: Binding(
                        get: { number.isVerified },
                        set: { model.setVerified($0, chatID: chatID) }))
                        .accessibilityIdentifier("verify.toggle")
                    Text("If the numbers change, someone added or changed a device. Compare them again.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                } else {
                    Text("Send a message first to see the safety number.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("verify.empty")
                }
                Spacer()
            }
            .padding()
            .navigationTitle("Verify")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
