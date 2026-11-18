import AppCore
import PhotosUI
import SwiftUI
#if os(iOS)
import UIKit
#endif

extension AppModel {
    /// The photo for an account ID: a contact's (from their profile) or the own one. `nil` shows the coloured letter.
    func profilePhoto(for accountID: String) -> Data? { _ = revision; return chatStore.profilePhoto(for: accountID) }
    var myPhoto: Data? { _ = revision; return chatStore.myPhoto }
    @discardableResult func setMyPhoto(_ photo: Data?) -> Bool { chatStore.setMyPhoto(photo) }
}

/// The first section of the profile screen: the photo, "Change photo" (the system photo picker, and the camera on an
/// iPhone) and "Remove photo". A picture that cannot be made small enough is refused with a plain message.
struct ProfilePhotoSection: View {
    @Bindable var model: AppModel
    @State private var showPicker = false
    @State private var pickedItem: PhotosPickerItem?
    @State private var failed = false
    @State private var working = false
    #if os(iOS)
    @State private var showCamera = false
    @State private var cameraDenied = false
    #endif

    private var pickerOpen: Bool {
        #if os(iOS)
        return showPicker || showCamera
        #else
        return showPicker
        #endif
    }

    var body: some View {
        Section {
            HStack(spacing: 16) {
                Avatar(name: model.me.displayName, colorKey: model.me.id, size: 72)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Your profile photo")
                    .accessibilityIdentifier("profile.photo")
                Menu {
                    Button("Choose photo", systemImage: "photo") { showPicker = true }
                    #if os(iOS)
                    if CameraPicker.isAvailable {
                        Button("Take photo", systemImage: "camera") { Task { await openCamera() } }
                    }
                    #endif
                } label: {
                    Text("Change photo")
                }
                .disabled(working)
                .accessibilityIdentifier("profile.photo.change")
            }
            if model.myPhoto != nil {
                Button("Remove photo", role: .destructive) {
                    failed = false
                    model.setMyPhoto(nil)
                }
                .disabled(working)
                .accessibilityIdentifier("profile.photo.remove")
            }
            if failed {
                Text("Couldn't use that photo. Try another.")
                    .font(.footnote).foregroundStyle(.red)
                    .accessibilityIdentifier("profile.photo.error")
            }
        }
        .expectsReturn(when: pickerOpen)
        .photosPicker(isPresented: $showPicker, selection: $pickedItem, matching: .images)
        .onChange(of: pickedItem) { _, item in
            guard let item else { return }
            pickedItem = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self) else { failed = true; return }
                await use(data)
            }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker(
                photosOnly: true,
                onPhoto: { url in
                    showCamera = false
                    let data = try? Data(contentsOf: url)
                    try? FileManager.default.removeItem(at: url)
                    Task { if let data { await use(data) } else { failed = true } }
                },
                onVideo: { url in try? FileManager.default.removeItem(at: url); showCamera = false },
                onCancel: { showCamera = false })
                .ignoresSafeArea()
        }
        .alert("Camera access is off", isPresented: $cameraDenied) {
            Button("Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Turn it on in Settings to take photos.")
        }
        #endif
    }

    /// Makes the JPEG off the main thread, then keeps it; the new profile goes out from the store.
    private func use(_ data: Data) async {
        working = true
        defer { working = false }
        let jpeg = await Task.detached(priority: .userInitiated) { ProfilePhotoEncoder.jpeg(from: data) }.value
        guard let jpeg, model.setMyPhoto(jpeg) else { failed = true; return }
        failed = false
    }

    #if os(iOS)
    private func openCamera() async {
        switch await CameraPicker.requestAccess() {
        case .allowed: showCamera = true
        case .denied: cameraDenied = true
        }
    }
    #endif
}
