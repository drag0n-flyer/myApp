import AppCore
import SwiftUI

#if os(iOS)
/// The Contacts tab.
struct ContactsView: View {
    @Bindable var model: AppModel
    let showsSearchField: Bool
    @State private var query = ""

    var body: some View {
        NavigationStack {
            ContactsContent(model: model, query: query) { contactID in
                model.openChatID = model.openChat(with: contactID)
                model.selectedTab = .chats
            }
            .navigationTitle("Contacts")
            .modifier(ContactsSearch(isOn: showsSearchField, text: $query))
        }
    }
}

private struct ContactsSearch: ViewModifier {
    let isOn: Bool
    @Binding var text: String

    func body(content: Content) -> some View {
        if isOn { content.searchable(text: $text, prompt: "Search") } else { content }
    }
}
#endif

/// Invite and add rows, then contacts in alphabetical sections. Shared by iPhone and Mac.
struct ContactsContent: View {
    @Bindable var model: AppModel
    var query: String = ""
    let openChat: (String) -> Void
    @State private var showingAdd = false

    private var inviteURL: URL {
        URL(string: "https://\(model.server.host)/invite/\(SampleAccountService.sampleInviteCode)")!
    }

    private var sections: [(letter: String, people: [Contact])] {
        let people = model.contacts
            .filter { query.isEmpty || $0.displayName.localizedCaseInsensitiveContains(query) || $0.username.contains(query.lowercased()) }
            .sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }
        let grouped = Dictionary(grouping: people) { String($0.displayName.prefix(1)).uppercased() }
        return grouped.keys.sorted { $0.localizedCompare($1) == .orderedAscending }.map { ($0, grouped[$0] ?? []) }
    }

    var body: some View {
        List {
            Section {
                ShareLink(item: inviteURL, message: Text("Join me on \(AppBrand.name)")) {
                    Label("Invite people", systemImage: "person.badge.plus")
                }
                .accessibilityIdentifier("contacts.invite")
                Button { showingAdd = true } label: {
                    Label("Add contact", systemImage: "plus.circle")
                }
                .accessibilityIdentifier("contacts.add")
            }
            ForEach(sections, id: \.letter) { section in
                Section(section.letter) {
                    ForEach(section.people) { person in
                        Button { openChat(person.id) } label: {
                            HStack(spacing: 12) {
                                Avatar(name: person.displayName, size: 38)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(person.displayName).foregroundStyle(.primary).lineLimit(1)
                                    Text("@\(person.username)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("contacts.row.\(person.id)")
                    }
                }
            }
        }
        .sheet(isPresented: $showingAdd) {
            AddContactView(model: model)
        }
    }
}
