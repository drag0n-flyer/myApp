import AppCore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Floating glass composer: attach, message field, mic or send.
struct ComposerView: View {
    @Binding var text: String
    let send: () -> Void
    /// Sample-data stand-in for attachments and voice recording.
    let sendSample: (MessageContent) -> Void
    /// Real attachments and voice notes. When nil, the sample stand-ins above are used.
    var sendAttachment: ((ComposerAttachment) -> Void)?

    @State private var voice = VoiceRecordingController()
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var photoItem: PhotosPickerItem?

    private var isEmpty: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(alignment: .bottom, spacing: 7) {
            Menu {
                Button("Photo", systemImage: "photo") {
                    if sendAttachment == nil { sendSample(.photo(bytes: 240_000)) } else { showPhotos = true }
                }
                Button("Camera", systemImage: "camera") { sendSample(.photo(bytes: 310_000)) }
                Button("File", systemImage: "doc") {
                    if sendAttachment == nil { sendSample(.file(name: "Notes.pdf", bytes: 180_000)) } else { showFiles = true }
                }
                Button("Location", systemImage: "location") { sendSample(.text(String(localized: "📍 Shared location"))) }
            } label: {
                Image(systemName: "plus")
                    .font(.title3.weight(.medium))
                    .frame(width: 42, height: 42)
                    .glassBackground(in: Circle())
            }
            .accessibilityLabel("Attach")
            .photosPicker(isPresented: $showPhotos, selection: $photoItem, matching: .images)
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item]) { result in
                guard case .success(let url) = result else { return }
                importFile(url)
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                photoItem = nil
                Task { await importPhoto(item) }
            }

            TextField("Message", text: $text, axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .frame(minHeight: 42)
                .glassBackground(in: RoundedRectangle(cornerRadius: 21))
                .accessibilityIdentifier("chat.composer.field")
                .onSubmit(send)

            if voice.isRecording {
                Button { voice.cancel() } label: {
                    Image(systemName: "trash")
                        .font(.title3)
                        .frame(width: 42, height: 42)
                        .glassBackground(in: Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.red)
                .accessibilityLabel("Cancel recording")
                .accessibilityIdentifier("chat.composer.cancelRecording")
                Button {
                    if let (url, seconds) = voice.finish() { sendAttachment?(.voice(url, duration: seconds)) }
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.title3)
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(Circle().fill(.red))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Stop and send voice message")
                .accessibilityIdentifier("chat.composer.stopRecording")
            } else if isEmpty {
                Button {
                    if sendAttachment != nil {
                        Task { await voice.start() }
                    } else {
                        sendSample(.voice(duration: 5))
                    }
                } label: {
                    Image(systemName: "mic")
                        .font(.title3)
                        .frame(width: 42, height: 42)
                        .glassBackground(in: Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
                .accessibilityLabel("Record voice message")
                .accessibilityIdentifier("chat.composer.mic")
            } else {
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(Circle().fill(.tint))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send")
                .accessibilityIdentifier("chat.composer.send")
            }
        }
        .padding(.horizontal, 10)
        .padding(.bottom, 6)
        .onDisappear { voice.cancel() }
        .alert("Microphone access is off", isPresented: Binding(get: { voice.denied }, set: { _ in voice.dismissDenied() })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Turn it on in Settings to record voice messages.")
        }
    }

    // MARK: - Importing

    private func importFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let copy = copyToTemp(url, name: url.lastPathComponent) else { return }
        sendAttachment?(.file(copy))
    }

    private func importPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("photo-\(UUID().uuidString.lowercased())")
        guard (try? data.write(to: url)) != nil else { return }
        sendAttachment?(.photo(url))
    }

    private func copyToTemp(_ url: URL, name: String) -> URL? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString.lowercased())
        let dest = dir.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: url, to: dest)
            return dest
        } catch { return nil }
    }
}
