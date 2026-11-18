import AppCore
import PhotosUI
import SwiftUI

/// Change a group's name and photo (owner and admins). Each change goes out as one `group` message.
struct GroupEditView: View {
    let model: AppModel
    let info: GroupInfo
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var photo: Data?
    @State private var photoChanged = false
    @State private var pickerOpen = false
    @State private var pickedItem: PhotosPickerItem?
    @State private var saving = false
    @State private var message: String?

    init(model: AppModel, info: GroupInfo) {
        self.model = model
        self.info = info
        _name = State(initialValue: info.name ?? "")
        _photo = State(initialValue: GroupScreenRules.displayablePhoto(info.photo))
    }

    private var newName: String? { GroupScreenRules.editedName(name, current: info.name) }
    private var nameIsValid: Bool { GroupNameRule.clean(name) != nil }
    private var canSave: Bool { !saving && nameIsValid && (newName != nil || photoChanged) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Group name", text: $name)
                        .accessibilityIdentifier("groupedit.name")
                }
                Section {
                    HStack(spacing: 12) {
                        if photoChanged, let photo, let image = GroupPhotoEncoder.preview(of: photo) {
                            Image(decorative: image, scale: 1)
                                .resizable().scaledToFill()
                                .frame(width: 56, height: 56).clipShape(Circle())
                        } else {
                            GroupPhotoView(photo: photo, size: 56)
                        }
                        Button("Choose photo") { pickerOpen = true }
                            .accessibilityLabel("Choose group photo")
                            .accessibilityIdentifier("groupedit.photo")
                        if photo != nil {
                            Spacer(minLength: 8)
                            Button("Remove photo", role: .destructive) { photo = nil; photoChanged = true }
                                .accessibilityIdentifier("groupedit.photo.remove")
                        }
                    }
                    .buttonStyle(.borderless)
                }
            }
            .navigationTitle("Edit")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(!canSave)
                        .accessibilityIdentifier("groupedit.save")
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
                    if let jpeg = await Task.detached(operation: { GroupPhotoEncoder.jpeg(from: data) }).value {
                        photo = jpeg
                        photoChanged = true
                    } else {
                        message = String(localized: "Choose a different photo.")
                    }
                }
            }
            .alert(message.map { LocalizedStringKey($0) } ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK") {}
            }
        }
    }

    private func save() {
        saving = true
        let chatID = info.chatID
        let renamed = newName
        let newPhoto = photo
        let changePhoto = photoChanged
        Task {
            var failure: String?
            if let renamed, let text = await model.renameGroup(chatID: chatID, name: renamed).message { failure = text }
            if failure == nil, changePhoto, let text = await model.setGroupPhoto(chatID: chatID, photo: newPhoto).message { failure = text }
            saving = false
            if let failure { message = failure } else { dismiss() }
        }
    }
}
