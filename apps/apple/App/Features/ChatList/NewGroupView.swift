import AppCore
import PhotosUI
import SwiftUI

/// New group: choose at least 2 people, then a name and an optional photo, then Create.
struct NewGroupView: View {
    let model: AppModel
    /// Called with the new group's chat ID once it exists (and after the person has read what could not be added).
    let onCreated: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var chosen: Set<String> = []
    @State private var showingDetails = false

    private var people: [Contact] {
        model.contacts.sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(people) { person in
                        let selected = chosen.contains(person.id)
                        Button {
                            if selected { chosen.remove(person.id) } else { chosen.insert(person.id) }
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
                        .accessibilityIdentifier("newgroup.person.\(person.id)")
                    }
                } footer: {
                    if GroupCreateRules.needsMorePeople(selected: chosen.count) {
                        Text("Choose at least 2 people")
                            .accessibilityIdentifier("newgroup.hint")
                    }
                }
            }
            .navigationTitle("Add people")
            .navigationDestination(isPresented: $showingDetails) {
                NewGroupDetailsView(model: model, accounts: chosen.sorted(), onCreated: onCreated)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Next") { showingDetails = true }
                        .disabled(GroupCreateRules.needsMorePeople(selected: chosen.count))
                        .accessibilityIdentifier("newgroup.next")
                }
            }
        }
    }
}

/// The second step: the group's name, an optional photo, and Create.
struct NewGroupDetailsView: View {
    let model: AppModel
    let accounts: [String]
    let onCreated: (String) -> Void
    @State private var name = ""
    @State private var photo: Data?
    @State private var pickerOpen = false
    @State private var pickedItem: PhotosPickerItem?
    @State private var creating = false
    @State private var message: String?
    @State private var createdChatID: String?

    private var canCreate: Bool { !creating && GroupCreateRules.canCreate(selected: accounts.count, name: name) }

    var body: some View {
        Form {
            Section {
                TextField("Group name", text: $name)
                    .accessibilityIdentifier("newgroup.name")
            }
            Section {
                HStack(spacing: 12) {
                    if let photo, let image = GroupPhotoEncoder.preview(of: photo) {
                        Image(decorative: image, scale: 1)
                            .resizable().scaledToFill()
                            .frame(width: 56, height: 56).clipShape(Circle())
                    } else {
                        Image(systemName: "person.3.fill")
                            .frame(width: 56, height: 56)
                            .background(Circle().fill(Color.neutralAvatarTile))
                            .foregroundStyle(.white)
                            .accessibilityHidden(true)
                    }
                    Button("Choose photo") { pickerOpen = true }
                        .accessibilityLabel("Choose group photo")
                        .accessibilityIdentifier("newgroup.photo")
                    if photo != nil {
                        Spacer(minLength: 8)
                        Button("Remove photo", role: .destructive) { photo = nil }
                            .accessibilityIdentifier("newgroup.photo.remove")
                    }
                }
                .buttonStyle(.borderless)
            }
        }
        .navigationTitle("New group")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Create") { create() }
                    .disabled(!canCreate)
                    .accessibilityIdentifier("newgroup.create")
            }
        }
        .expectsReturn(when: pickerOpen)
        .photosPicker(isPresented: $pickerOpen, selection: $pickedItem, matching: .images)
        .onChange(of: pickedItem) { _, item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                pickedItem = nil
                guard let data else { return }
                // The decode and the re-encode are bounded (a 256 pixel thumbnail) and run off the main actor.
                photo = await Task.detached { GroupPhotoEncoder.jpeg(from: data) }.value
            }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") {
                if let id = createdChatID { createdChatID = nil; onCreated(id) }
            }
        }
    }

    private func create() {
        creating = true
        Task {
            let outcome = await model.createGroup(accounts: accounts, name: name, photo: photo)
            creating = false
            switch outcome {
            case .created(let chatID, let notAdded):
                if notAdded.isEmpty {
                    onCreated(chatID)
                } else {
                    // Say who is not in yet, then open the group.
                    createdChatID = chatID
                    message = notAdded.map { id in
                        let who = model.contact(id: id)?.displayName ?? String(localized: "Someone")
                        return String(localized: "\(who) couldn't be added yet")
                    }.joined(separator: "\n")
                }
            case .failed(let failure):
                message = Self.text(for: failure)
            }
        }
    }

    private static func text(for failure: GroupCreateFailure) -> String {
        switch failure {
        case .invalidName: String(localized: "Choose a different name.")
        case .invalidPhoto: String(localized: "Choose a different photo.")
        case .notFound: String(localized: "One of these people could not be found.")
        case .full: String(localized: "This group is full.")
        case .noKeyPackages: String(localized: "These people can't be added yet. Try again later.")
        case .rateLimited: String(localized: "You've made too many groups. Try again later.")
        case .tooFewPeople: String(localized: "Choose at least 2 people")
        case .tooManyPeople, .offline, .other:
            String(localized: "Couldn't create the group. Check your connection and try again.")
        }
    }
}
