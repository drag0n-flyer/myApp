import AppCore
import SwiftUI

/// Create, rename, reorder, and delete custom folders. All and Unread are fixed.
struct ChatFoldersView: View {
    @Bindable var model: AppModel
    @State private var showingNew = false
    @State private var renaming: ChatFolder?
    @State private var newName = ""

    private var custom: [ChatFolder] { model.folders.filter { $0.kind == .custom } }

    var body: some View {
        List {
            Section {
                ForEach(model.folders.filter { $0.kind != .custom }) { folder in
                    Label(model.folderChipTitle(folder), systemImage: "lock").foregroundStyle(.secondary)
                }
            } footer: {
                Text("All and Unread are always there.")
            }
            Section {
                ForEach(custom) { folder in
                    Button(folder.name) {
                        newName = folder.name
                        renaming = folder
                    }
                }
                .onDelete { offsets in offsets.map { custom[$0].id }.forEach(model.deleteFolder) }
                .onMove { from, to in
                    guard let first = from.first else { return }
                    model.moveFolder(id: custom[first].id, toCustomIndex: to > first ? to - 1 : to)
                }
                Button("New folder", systemImage: "folder.badge.plus") { showingNew = true }
            }
        }
        .navigationTitle("Chat folders")
        #if os(iOS)
        .toolbar { EditButton() }
        #endif
        .sheet(isPresented: $showingNew) { NewFolderView(model: model) }
        .alert("Rename folder", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Save") {
                if let renaming, !newName.trimmingCharacters(in: .whitespaces).isEmpty {
                    model.renameFolder(id: renaming.id, to: newName)
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}

private struct NewFolderView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var chosen: Set<String> = []

    var body: some View {
        NavigationStack {
            Form {
                TextField("Folder name", text: $name)
                Section("Chats") {
                    ForEach(model.chats(in: .all)) { chat in
                        let title = model.contact(id: chat.contactID)?.displayName ?? ""
                        Toggle(title, isOn: Binding(
                            get: { chosen.contains(chat.id) },
                            set: { on in if on { chosen.insert(chat.id) } else { chosen.remove(chat.id) } }
                        ))
                    }
                }
            }
            .navigationTitle("New folder")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        model.addFolder(name: name.trimmingCharacters(in: .whitespaces), chatIDs: chosen)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
