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
                    Label(chat.encryption.infoText, systemImage: chat.encryption == .endToEnd ? "lock" : "server.rack")
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(chat.encryption.infoText))
                        .accessibilityIdentifier("chatinfo.encryption")
                    Button("Verify", systemImage: "checkmark.shield") { showingVerify = true }
                }

                Section {
                    LabeledContent("Disappearing messages", value: String(localized: "Off"))
                }

                Section {
                    Button("Report", systemImage: "exclamationmark.bubble", role: .destructive) { model.report(chatID: chatID) }
                    Button("Block \(contact.displayName)", systemImage: "hand.raised", role: .destructive) { confirmBlock = true }
                }
            }
        }
        .navigationTitle("Info")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .sheet(isPresented: $showingVerify) {
            VerifyView(name: contact?.displayName ?? "")
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

/// Safety code to compare in person or over a call.
private struct VerifyView: View {
    let name: String
    @Environment(\.dismiss) private var dismiss
    private let code = ["37281", "90114", "52860", "11793", "64025", "88341",
                        "20957", "73618", "45502", "39184", "61276", "08433"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Compare these numbers with \(name). If they match, your chat is safe.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 12) {
                    ForEach(code, id: \.self) { Text($0).font(.title3.monospacedDigit()) }
                }
                .padding()
                .background(Color.secondaryBackground, in: RoundedRectangle(cornerRadius: 16))
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
