import AppCore
import SwiftUI

/// "Media and files" of one chat (chat info): a grid of photos and videos grouped by month, and a list of files.
struct MediaGalleryView: View {
    let chatID: String
    @Bindable var model: AppModel

    private enum Tab: Hashable { case media, files }

    @State private var tab = Tab.media
    @State private var preview = MediaPreview()
    @State private var viewer: ViewerRequest?

    var body: some View {
        // Read after every store change, so a finished download shows at once.
        let _ = model.revision
        let all = model.chatAttachmentMessages(in: chatID)
        let media = ChatMedia.mediaMessages(all)
        let files = ChatMedia.fileMessages(all)
        VStack(spacing: 0) {
            Picker("Show", selection: $tab) {
                Text("Media").tag(Tab.media)
                Text("Files").tag(Tab.files)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .accessibilityIdentifier("gallery.tabs")

            switch tab {
            case .media: mediaTab(media)
            case .files: filesTab(files)
            }
        }
        .navigationTitle("Media and files")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .mediaPreview(preview)
        .mediaViewer($viewer)
        .onDisappear { preview.close() }
        .accessibilityIdentifier("gallery")
    }

    // MARK: - Media

    @ViewBuilder
    private func mediaTab(_ media: [Message]) -> some View {
        if media.isEmpty {
            ContentUnavailableView("No media yet", systemImage: "photo.on.rectangle")
                .accessibilityIdentifier("gallery.media.empty")
        } else {
            let byID = Dictionary(media.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            let months = GalleryGroups.byMonth(media.map { GalleryItem(messageID: $0.id, date: $0.sentAt) }, zone: .current)
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2, pinnedViews: [.sectionHeaders]) {
                    ForEach(months, id: \.key) { month in
                        Section {
                            ForEach(month.items, id: \.messageID) { item in
                                if let message = byID[item.messageID] { cell(message) }
                            }
                        } header: {
                            Text(month.title)
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(.bar)
                                .accessibilityAddTraits(.isHeader)
                        }
                    }
                }
            }
            .accessibilityIdentifier("gallery.media")
        }
    }

    private func cell(_ message: Message) -> some View {
        let state = MediaBubbleState(media: message.media, fileExists: model.mediaFileIsUsable)
        let isVideo = ChatMedia.kind(of: message) == .video
        return Color.secondary.opacity(0.18)
            .aspectRatio(1, contentMode: .fit)
            .overlay { GalleryCell(state: state, isVideo: isVideo, sample: model.isSampleData) }
            .clipped()
            .contentShape(Rectangle())
            .onTapGesture { tapped(message, state) }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(isVideo ? Text("Video") : Text("Photo"))
            .accessibilityHint(Text(hint(state)))
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("gallery.media.\(message.id)")
    }

    private func hint(_ state: MediaBubbleState) -> LocalizedStringKey {
        switch state {
        case .ready: "Opens it"
        case .notDownloaded: "Download"
        case .downloading: "Downloading"
        case .failed: "Try again"
        }
    }

    private func tapped(_ message: Message, _ state: MediaBubbleState) {
        switch state {
        case .ready:
            if let request = model.viewerRequest(chatID: chatID, startingAt: message.id) { viewer = request }
        case .notDownloaded, .failed:
            if !model.isSampleData { model.downloadMedia(messageID: message.id, in: chatID) }
        case .downloading:
            break
        }
    }

    // MARK: - Files

    @ViewBuilder
    private func filesTab(_ files: [Message]) -> some View {
        if files.isEmpty {
            ContentUnavailableView("No files yet", systemImage: "doc")
                .accessibilityIdentifier("gallery.files.empty")
        } else {
            List(files) { message in
                fileRow(message)
            }
            .listStyle(.plain)
            .accessibilityIdentifier("gallery.files")
        }
    }

    @ViewBuilder
    private func fileRow(_ message: Message) -> some View {
        if case .file(let rawName, let bytes) = message.content {
            let name = MediaFileName.clean(rawName)
            let state = MediaBubbleState(media: message.media, fileExists: model.mediaFileIsUsable)
            Button {
                switch state {
                case .ready(let url):
                    preview.open(source: url, mediaDirectory: model.mediaDirectory, name: name, isPhoto: false)
                case .notDownloaded, .failed:
                    if !model.isSampleData { model.downloadMedia(messageID: message.id, in: chatID) }
                case .downloading:
                    break
                }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "doc.fill").font(.title3).foregroundStyle(.tint).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(.body).lineLimit(2)
                        Text("\(DataFormat.size(bytes: bytes)) · \(message.sentAt.formatted(date: .abbreviated, time: .omitted))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                    stateGlyph(state)
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("\(name), \(DataFormat.size(bytes: bytes))"))
            .accessibilityHint(Text(hint(state)))
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("gallery.file.\(message.id)")
        }
    }

    @ViewBuilder
    private func stateGlyph(_ state: MediaBubbleState) -> some View {
        switch state {
        case .ready: EmptyView()
        case .notDownloaded: Image(systemName: "arrow.down.circle").foregroundStyle(.secondary).accessibilityHidden(true)
        case .downloading(let fraction): ProgressView(value: fraction).progressViewStyle(.circular).frame(width: 22, height: 22)
        case .failed: Image(systemName: "exclamationmark.arrow.triangle.2.circlepath").foregroundStyle(.secondary).accessibilityHidden(true)
        }
    }
}

private extension GalleryMonth {
    var key: String { "\(year)-\(month)" }

    /// "November 2026"
    var title: String {
        var parts = DateComponents()
        parts.year = year
        parts.month = month
        parts.day = 1
        let date = Calendar.current.date(from: parts) ?? .distantPast
        return date.formatted(.dateTime.month(.wide).year())
    }
}

/// One tile of the Media grid: the picture or video frame when the file is here, otherwise the Download state.
private struct GalleryCell: View {
    let state: MediaBubbleState
    let isVideo: Bool
    let sample: Bool
    @Environment(\.displayScale) private var displayScale
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            switch state {
            case .ready(let url):
                if let image {
                    Image(decorative: image, scale: displayScale).resizable().scaledToFill()
                }
                if isVideo {
                    Image(systemName: "play.fill")
                        .font(.body)
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(Circle().fill(.black.opacity(0.45)))
                }
                Color.clear.task(id: url) { await load(url) }
            case .notDownloaded:
                Image(systemName: sample ? (isVideo ? "video" : "photo") : "arrow.down.circle")
                    .font(.title3).foregroundStyle(.secondary)
            case .downloading(let fraction):
                ProgressView(value: fraction).progressViewStyle(.circular)
            case .failed:
                Image(systemName: "exclamationmark.arrow.triangle.2.circlepath").font(.title3).foregroundStyle(.secondary)
            }
        }
    }

    private func load(_ url: URL) async {
        let pixels = Int((200 * displayScale).rounded(.up))
        if isVideo {
            image = await VideoPoster.shared.info(at: url, maxPixel: pixels).image
        } else {
            image = await ThumbnailLoader.shared.image(at: url, maxPixel: pixels)?.cgImage
        }
    }
}
