import AppCore
import SwiftUI

/// Add members: choose from the contacts who are not in the group yet, then Add.
struct GroupAddMembersView: View {
    let model: AppModel
    let info: GroupInfo
    @Environment(\.dismiss) private var dismiss
    @State private var chosen: Set<String> = []
    @State private var adding = false
    @State private var message: String?

    private var room: Int { GroupScreenRules.room(memberCount: info.members.count) }

    private var people: [Contact] {
        let ids = Set(GroupScreenRules.addCandidates(contactIDs: model.contacts.map(\.id), members: info.members))
        return model.contacts.filter { ids.contains($0.id) }
            .sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(people) { person in
                        let selected = chosen.contains(person.id)
                        Button {
                            if selected { chosen.remove(person.id) } else if chosen.count < room { chosen.insert(person.id) }
                        } label: {
                            HStack(spacing: 12) {
                                Avatar(name: person.displayName, colorKey: person.id, size: 36)
                                VStack(alignment: .leading) {
                                    Text(person.displayName).foregroundStyle(.primary)
                                    Text("@\(person.username)").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 8)
                                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                            }
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                        .accessibilityIdentifier("groupadd.person.\(person.id)")
                    }
                } footer: {
                    if room == 0 { Text("This group is full.") }
                }
            }
            .overlay {
                if people.isEmpty {
                    ContentUnavailableView("Everyone you know is already here", systemImage: "person.3")
                }
            }
            .navigationTitle("Add members")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { add() }
                        .disabled(chosen.isEmpty || adding)
                        .accessibilityIdentifier("groupadd.confirm")
                }
            }
            .alert(message.map { LocalizedStringKey($0) } ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK") {}
            }
        }
    }

    private func add() {
        adding = true
        let accounts = chosen.sorted()
        let chatID = info.chatID
        Task {
            let outcome = await model.addMembers(chatID: chatID, accounts: accounts)
            adding = false
            if let text = outcome.message { message = text } else { dismiss() }
        }
    }
}
