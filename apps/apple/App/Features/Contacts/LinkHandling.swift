import AppCore
import SwiftUI

/// Shows what an opened link leads to: the add-contact confirmation, the chat, or a short message. Handles the waiting
/// link once the app can (unlocked, signed in); while the lock cover is up nothing is presented.
@MainActor
struct LinkHandling: ViewModifier {
    let model: AppModel
    private var links: LinkCoordinator { AppLinks.coordinator }

    private struct Trigger: Equatable {
        var received: Int
        var outcome: LinkOutcome?
        var ready: Bool
    }

    func body(content: Content) -> some View {
        content
            // Watches the outcome too: a chat to open that arrived while locked is acted on at unlock, and a link that
            // waited behind a shown outcome is taken when that one is dismissed. The task is unstructured so a lock
            // that comes up meanwhile does not cancel a redeem that already started.
            .onChange(of: Trigger(received: links.received, outcome: links.outcome, ready: model.linkReady), initial: true) {
                if model.linkReady, case .openChat(let contactID) = links.outcome {
                    model.selectedTab = .chats
                    model.openChatID = model.openChat(with: contactID)
                    links.dismiss()
                }
                Task { await model.processLinks() }
            }
            .sheet(item: confirmation) { person in
                LinkConfirmView(model: model, person: person)
            }
            .alert(messageTitle, isPresented: messageShown) {
                Button("OK", role: .cancel) { if model.linkReady { links.dismiss() } }
            }
    }

    // Nothing is presented while the lock cover is up, whatever the coordinator holds.
    private var confirmation: Binding<PersonToAdd?> {
        Binding(
            get: {
                guard model.linkReady, case .confirmAdd(let username, let name) = links.outcome else { return nil }
                return PersonToAdd(username: username, displayName: name)
            },
            // A lock that comes up must not drop an outcome (its token may be spent): only a dismissal on screen counts.
            set: { if $0 == nil, model.linkReady { links.dismiss() } }
        )
    }

    private var message: LinkMessage? {
        guard model.linkReady, case .message(let m) = links.outcome else { return nil }
        return m
    }

    private var messageTitle: LocalizedStringKey { LocalizedStringKey(message?.text ?? "") }

    private var messageShown: Binding<Bool> {
        Binding(get: { message != nil }, set: { if !$0, model.linkReady { links.dismiss() } })
    }
}

extension View {
    func linkHandling(_ model: AppModel) -> some View { modifier(LinkHandling(model: model)) }
}

struct PersonToAdd: Identifiable, Equatable {
    var username: String
    var displayName: String?
    var id: String { username }
}

/// "Add this person?" for a link that was opened or scanned.
struct LinkConfirmView: View {
    let model: AppModel
    let person: PersonToAdd
    @Environment(\.dismiss) private var dismiss
    @State private var notFound = false
    @State private var adding = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Avatar(name: person.displayName ?? person.username, colorKey: person.username, size: 88)
                    .accessibilityHidden(true)
                if let name = person.displayName {
                    Text(name).font(.title2.weight(.semibold))
                }
                Text("@\(person.username)")
                    .font(person.displayName == nil ? .title2.weight(.semibold) : .body)
                    .foregroundStyle(person.displayName == nil ? .primary : .secondary)
                    .accessibilityIdentifier("link.confirm.username")
                if notFound {
                    Text("No one with that username on this server.").font(.footnote).foregroundStyle(.red)
                }
                Button("Add", action: add)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(adding)
                    .padding(.top, 12)
                    .accessibilityIdentifier("link.confirm.add")
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("Add contact")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 320)
        #endif
    }

    private func add() {
        adding = true
        Task {
            if let contact = await model.addContact(username: person.username) {
                dismiss()
                model.selectedTab = .chats
                model.openChatID = model.openChat(with: contact.id)
            } else {
                notFound = true
                adding = false
            }
        }
    }
}
