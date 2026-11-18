import AppCore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#endif

/// Floating glass composer: attach, message field, mic or send.
struct ComposerView: View {
    @Binding var text: String
    let send: () -> Void
    /// Sample-data stand-in for attachments and voice recording.
    let sendSample: (MessageContent) -> Void
    /// Real attachments and voice notes. When nil, the sample stand-ins above are used.
    var sendAttachment: ((ComposerAttachment) -> Void)?
    /// Photos and videos from the preview screen, with the caption.
    var sendMedia: (([OutgoingMedia], String) -> Void)?
    /// The largest video that can be sent.
    var attachmentLimit = SendPlan.defaultLimitBytes
    /// Sample data has no photo library: Photo opens the preview with a stand-in picture.
    var sampleMedia = false
    /// The message a reply is being written to; shows the reply bar.
    var replyingTo: Message?
    /// Display name of the person `replyingTo` is from.
    var replyingToName = ""
    /// The own message being edited; shows the "Editing" bar.
    var editing: Message?
    /// Closes whichever bar is showing.
    var cancelBar: () -> Void = {}

    @State private var voice = VoiceRecordingController()
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var photoItems: [PhotosPickerItem] = []
    /// Photos and videos on the preview screen; nothing is handed to the engine before Send.
    @State private var draftMedia: [DraftMedia] = []
    @State private var videoTooBig = false
    #if os(iOS)
    @State private var showCamera = false
    @State private var cameraDenied = false
    /// One of the app's own pickers or the camera is up (app lock does not count that as leaving).
    private var pickerOpen: Bool { showPhotos || showFiles || showCamera }
    #else
    private var pickerOpen: Bool { showPhotos || showFiles }
    #endif

    private var isEmpty: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(spacing: 6) {
            bar
            controls
        }
        .padding(.horizontal, 10)
        .padding(.bottom, 6)
    }

    /// Reply or edit bar above the field.
    @ViewBuilder
    private var bar: some View {
        if editing != nil || replyingTo != nil {
            HStack(spacing: 10) {
                Image(systemName: editing != nil ? "pencil" : "arrowshape.turn.up.left")
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    if let editing {
                        Text("Editing").font(.caption.weight(.semibold))
                        Text(editing.previewText).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    } else if let replyingTo {
                        Text("Replying to \(replyingToName)").font(.caption.weight(.semibold))
                        Text(replyingTo.previewText).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                Button(action: cancelBar) {
                    Image(systemName: "xmark.circle.fill").font(.title3).foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Cancel")
                .accessibilityIdentifier("chat.bar.close")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .glassBackground(in: RoundedRectangle(cornerRadius: 16))
            // Contain, so the close button keeps its own identifier instead of taking the bar's.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(editing != nil ? "chat.editbar" : "chat.replybar")
        }
    }

    private var controls: some View {
        HStack(alignment: .bottom, spacing: 7) {
            Menu {
                Button("Photo", systemImage: "photo") {
                    if sampleMedia {
                        draftMedia = [DraftMedia(url: nil, kind: .photo, bytes: 240_000)]
                    } else if sendAttachment == nil { sendSample(.photo(bytes: 240_000)) } else { showPhotos = true }
                }
                #if os(iOS)
                if sendAttachment == nil {
                    Button("Camera", systemImage: "camera") { sendSample(.photo(bytes: 310_000)) }
                } else if CameraPicker.isAvailable {
                    Button("Camera", systemImage: "camera") { Task { await openCamera() } }
                }
                #endif
                Button("File", systemImage: "doc") {
                    if sendAttachment == nil { sendSample(.file(name: "Notes.pdf", bytes: 180_000)) } else { showFiles = true }
                }
            } label: {
                Image(systemName: "plus")
                    .font(.title3.weight(.medium))
                    .frame(width: 42, height: 42)
                    .glassBackground(in: Circle())
            }
            .accessibilityLabel("Attach")
            .expectsReturn(when: pickerOpen)
            .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: SendPlan.maxItems,
                          matching: .any(of: [.images, .videos]))
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item]) { result in
                guard case .success(let url) = result else { return }
                importFile(url)
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker(
                    onPhoto: { url in showCamera = false; showPreview(of: [draft(url, .photo)]) },
                    onVideo: { url in showCamera = false; showPreview(of: [draft(url, .video)]) },
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
            .onChange(of: photoItems) { _, items in
                guard !items.isEmpty else { return }
                photoItems = []
                Task { await importPicked(items) }
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
        .onDisappear { voice.cancel() }
        .sheet(isPresented: Binding(get: { !draftMedia.isEmpty }, set: { if !$0 { discardDraft() } })) {
            SendPreviewView(items: draftMedia, limitBytes: attachmentLimit, send: sendDraft, cancel: discardDraft)
        }
        .alert("This video is too big to send.", isPresented: $videoTooBig) {
            Button("OK", role: .cancel) {}
        }
        .alert("Microphone access is off", isPresented: Binding(get: { voice.denied }, set: { _ in voice.dismissDenied() })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Turn it on in Settings to record voice messages.")
        }
    }

    // MARK: - Importing

    #if os(iOS)
    private func openCamera() async {
        switch await CameraPicker.requestAccess() {
        case .allowed: showCamera = true
        case .denied: cameraDenied = true
        }
    }
    #endif

    private func importFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let copy = copyToTemp(url, name: url.lastPathComponent) else { return }
        sendAttachment?(.file(copy))
    }

    /// Loads what the person picked (photos and videos) into the staging folder and opens the preview. A video over
    /// the limit is refused here, before anything is kept.
    private func importPicked(_ items: [PhotosPickerItem]) async {
        var added: [DraftMedia] = []
        for item in items.prefix(SendPlan.maxItems) {
            if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
                guard let movie = try? await item.loadTransferable(type: PickedMovie.self) else { continue }
                let bytes = Self.fileSize(movie.url)
                if bytes > attachmentLimit {
                    try? FileManager.default.removeItem(at: movie.url)
                    videoTooBig = true
                    continue
                }
                added.append(DraftMedia(url: movie.url, kind: .video, bytes: bytes))
            } else {
                guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
                let folder = MediaPreview.stagingFolder
                let url = folder.appendingPathComponent("photo-\(UUID().uuidString.lowercased())")
                guard (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil,
                      (try? data.write(to: url, options: .completeFileProtection)) != nil else { continue }
                added.append(DraftMedia(url: url, kind: .photo, bytes: Int64(data.count)))
            }
        }
        showPreview(of: added)
    }

    private func draft(_ url: URL, _ kind: PickedMedia.Kind) -> DraftMedia {
        DraftMedia(url: url, kind: kind, bytes: Self.fileSize(url))
    }

    private static func fileSize(_ url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }

    private func showPreview(of items: [DraftMedia]) {
        guard !items.isEmpty else { return }
        if let big = items.first(where: { $0.kind == .video && $0.bytes > attachmentLimit }) {
            if let url = big.url { try? FileManager.default.removeItem(at: url) }
            videoTooBig = true
            return
        }
        draftMedia = items
    }

    /// Send on the preview: the plan was accepted there, so the items go to the engine in order with the caption.
    private func sendDraft(caption: String) {
        let items = draftMedia.map { OutgoingMedia(url: $0.url ?? URL(fileURLWithPath: "/sample/Photo.jpg"), kind: $0.kind == .video ? .video : .photo) }
        draftMedia = []
        sendMedia?(items, caption)
    }

    /// Cancel on the preview: the picked copies are removed.
    private func discardDraft() {
        for item in draftMedia {
            if let url = item.url, MediaPathGuard.isInside(url, directory: MediaPreview.stagingFolder) { try? FileManager.default.removeItem(at: url) }
        }
        draftMedia = []
    }

    private func copyToTemp(_ url: URL, name: String) -> URL? {
        let dir = MediaPreview.stagingFolder.appendingPathComponent(UUID().uuidString.lowercased())
        let dest = dir.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: url, to: dest)
            return dest
        } catch { return nil }
    }
}
