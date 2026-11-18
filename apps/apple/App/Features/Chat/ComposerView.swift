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
    /// Sample data has no microphone: a stand-in recorder is used.
    var sampleMode = false
    /// True while a call is on; a recording in progress stops and is kept as a preview.
    var callActive = false

    @State private var session: VoiceSession?
    /// The finger is down on the microphone (iPhone).
    #if os(iOS)
    @GestureState private var touching = false
    @State private var pressSent = false
    @State private var touchReleased = false
    #endif
    @State private var previewPlayer = VoicePlayer()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.layoutDirection) private var layoutDirection
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
    /// Editing a photo or file: an empty field is allowed, it removes the caption.
    private var editsAttachment: Bool {
        switch editing?.content {
        case .photo?, .file?: true
        default: false
        }
    }

    var body: some View {
        VStack(spacing: 6) {
            bar
            if session?.hintVisible == true {
                Text("Hold to record, release to send")
                    .font(.footnote)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .glassBackground(in: Capsule())
                    .accessibilityIdentifier("chat.voice.hint")
            }
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
            if let session, showsVoiceBar(session) {
                voiceBar(session)
            } else {
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
            }

            if let session, session.state == .locked || session.state == .preview {
                Button {
                    session.send()
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(Circle().fill(.tint))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send voice message")
                .accessibilityIdentifier("chat.voice.send")
            } else if (isEmpty && !editsAttachment) || session?.isActive == true {
                micButton
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
        .onAppear { makeSession() }
        .onDisappear { session?.interrupted(); previewPlayer.stop() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { session?.interrupted() } }
        .onChange(of: callActive) { _, active in
            previewPlayer.callStateChanged(inCall: active)
            if active { session?.interrupted() }
        }
        .onChange(of: session?.state) { old, new in voiceStateChanged(from: old, to: new) }
        .sheet(isPresented: Binding(get: { !draftMedia.isEmpty }, set: { if !$0 { discardDraft() } })) {
            SendPreviewView(items: draftMedia, limitBytes: attachmentLimit, send: sendDraft, cancel: discardDraft)
        }
        .alert("This video is too big to send.", isPresented: $videoTooBig) {
            Button("OK", role: .cancel) {}
        }
        .alert("Microphone access is off", isPresented: Binding(get: { session?.denied ?? false }, set: { _ in session?.dismissDenied() })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Turn it on in Settings to record voice messages.")
        }
    }

    // MARK: - Voice notes

    private static let previewID = "voice-preview"

    private func makeSession() {
        guard session == nil else { return }
        let capture: VoiceCapture = sampleMode ? SampleVoiceCapture() : VoiceRecordingController()
        let made = VoiceSession(capture: capture, rtl: layoutDirection == .rightToLeft) { [sendAttachment, sendSample] url, seconds in
            if let sendAttachment {
                sendAttachment(.voice(url, duration: seconds))
                // The store has copied it into its own folder; the plaintext recording does not stay in temp.
                try? FileManager.default.removeItem(at: url)
            } else {
                try? FileManager.default.removeItem(at: url)
                sendSample(.voice(duration: seconds))
            }
        }
        previewPlayer.callActive = callActive
        session = made
    }

    /// The bar replaces the field once recording has started (not while only waiting for the hold delay).
    private func showsVoiceBar(_ session: VoiceSession) -> Bool {
        switch session.state {
        case .recording, .willCancel, .willLock, .locked, .preview: true
        default: false
        }
    }

    @ViewBuilder
    private func voiceBar(_ session: VoiceSession) -> some View {
        HStack(spacing: 8) {
            switch session.state {
            case .locked:
                voiceIconButton("trash", label: "Delete recording", id: "chat.voice.delete") { session.delete() }
                recordingStatus(session, locked: true)
                voiceIconButton("stop.fill", label: "Stop recording", id: "chat.voice.stop") { session.stop() }
            case .preview:
                voiceIconButton("trash", label: "Delete recording", id: "chat.voice.delete") { session.delete() }
                previewControls(session)
            default:
                recordingStatus(session, locked: false)
            }
        }
        .padding(.horizontal, 8)
        .frame(minHeight: 42)
        .frame(maxWidth: .infinity)
        .glassBackground(in: Capsule())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat.voice.bar")
    }

    private func voiceIconButton(_ symbol: String, label: LocalizedStringKey, id: String,
                                 action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title3)
                .frame(width: 42, height: 42)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.red)
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }

    /// Red dot, time, level meter and, while holding, the slide hint.
    private func recordingStatus(_ session: VoiceSession, locked: Bool) -> some View {
        let toLeading = layoutDirection == .rightToLeft ? session.dragOffset.dx : -session.dragOffset.dx
        let cancelProgress = min(1, max(0, toLeading / VoiceGesture.cancelDistance))
        return HStack(spacing: 8) {
            Circle().fill(.red).frame(width: 10, height: 10)
            Text(Message.clockText(session.elapsed))
                .font(.body.monospacedDigit())
            VoiceLevelMeter(levels: session.levels)
                .frame(maxWidth: .infinity)
                .frame(height: 28)
            if !locked {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.backward")
                    Text("Slide to cancel")
                }
                .font(.footnote)
                .foregroundStyle(session.state == .willCancel ? Color.red : Color.secondary)
                .opacity(1 - cancelProgress)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(locked ? Text("Recording locked") : Text("Recording"))
        .accessibilityValue(Text(Message.clockText(session.elapsed)))
        .accessibilityIdentifier("chat.voice.status")
    }

    private func previewControls(_ session: VoiceSession) -> some View {
        let playing = previewPlayer.isPlaying(Self.previewID)
        let progress = previewPlayer.playingMessageID == Self.previewID ? previewPlayer.progress : 0
        return HStack(spacing: 8) {
            Button {
                if let url = session.previewURL { previewPlayer.toggle(messageID: Self.previewID, url: url, isMine: true) }
            } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.title3)
                    .frame(width: 42, height: 42)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(playing ? Text("Pause") : Text("Play recording"))
            .accessibilityIdentifier("chat.voice.play")
            ProgressView(value: progress)
                .accessibilityHidden(true)
            Text(Message.clockText(session.previewDuration))
                .font(.body.monospacedDigit())
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat.voice.preview")
    }

    @ViewBuilder
    private var micButton: some View {
        let state = session?.state ?? .idle
        let holding = state == .recording || state == .willLock || state == .willCancel
        let lockProgress = min(1, max(0, -(session?.dragOffset.dy ?? 0) / VoiceGesture.lockDistance))
        let icon = Image(systemName: holding ? "mic.fill" : "mic")
            .font(.title3)
            .foregroundStyle(holding ? Color.white : Color.accentColor)
            .frame(width: 42, height: 42)
            .background(Circle().fill(holding ? Color.red : Color.clear))
            .glassBackground(in: Circle())
            .scaleEffect(holding ? 1.25 : 1)
            .overlay(alignment: .top) {
                if holding {
                    Image(systemName: state == .willLock ? "lock.fill" : "lock")
                        .font(.footnote)
                        .padding(8)
                        .glassBackground(in: Circle())
                        .opacity(0.4 + 0.6 * lockProgress)
                        .offset(y: -(VoiceGesture.lockDistance + 14))
                        .accessibilityHidden(true)
                }
            }
        #if os(iOS)
        icon
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    // A GestureState goes back to false when the system cancels the touch (a permission alert,
                    // a call, the scene going inactive), which `onEnded` never reports.
                    .updating($touching) { _, state, _ in state = true }
                    .onChanged { value in
                        if !pressSent { pressSent = true; session?.press() }
                        session?.drag(dx: value.translation.width, dy: value.translation.height)
                    }
                    .onEnded { _ in
                        touchReleased = true
                        session?.release()
                    })
            .onChange(of: touching) { _, down in
                guard !down else { return }
                // Let `onEnded` of the same touch run first, then treat a touch that never released as interrupted.
                Task { @MainActor in
                    if !touchReleased { session?.touchEndedWithoutRelease() }
                    touchReleased = false
                    pressSent = false
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Record voice message")
            .accessibilityAddTraits(.isButton)
            // VoiceOver and Switch Control cannot hold or drag: activating starts a locked recording.
            .accessibilityAction { Task { await session?.activateAccessible() } }
            .accessibilityIdentifier("chat.composer.mic")
        #else
        // The Mac never uses hold: a click starts a locked recording.
        Button { Task { await session?.activateAccessible() } } label: { icon }
            .buttonStyle(.plain)
            .accessibilityLabel("Record voice message")
            .accessibilityIdentifier("chat.composer.mic")
        #endif
    }

    private func voiceStateChanged(from old: VoiceGesture.State?, to new: VoiceGesture.State?) {
        if new == .locked { AccessibilityNotification.Announcement(String(localized: "Recording locked")).post() }
        if old == .preview && new == .idle { previewPlayer.stop() }
        #if os(iOS)
        switch (old, new) {
        case (_, .recording?):
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case (_, .willCancel?), (_, .willLock?), (_, .locked?):
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case (.willCancel?, .idle?):
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
        default: break
        }
        #endif
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
        let items = draftMedia.map { OutgoingMedia(url: $0.url ?? URL(fileURLWithPath: "/sample/Photo.jpg"), kind: $0.kind == .video ? .video : .photo, deleteAfterStaging: $0.url != nil) }
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
