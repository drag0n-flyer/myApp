import AppCore
import AVKit
import SwiftUI
#if os(iOS)
import Photos
#else
import AppKit
#endif

/// The photos and videos the viewer swipes through, and where it starts.
struct ViewerRequest: Identifiable {
    let id = UUID()
    let items: [ChatMedia.ViewerItem]
    let start: Int
}

extension View {
    /// Shows the viewer full screen (a sheet on the Mac) while `request` is set. The app's own viewer counts as an
    /// expected return for the app lock.
    func mediaViewer(_ request: Binding<ViewerRequest?>) -> some View {
        #if os(iOS)
        return fullScreenCover(item: request) { MediaViewerView(request: $0) }
            .expectsReturn(when: request.wrappedValue != nil)
        #else
        return sheet(item: request) { MediaViewerView(request: $0) }
            .expectsReturn(when: request.wrappedValue != nil)
        #endif
    }
}

/// Full-screen viewer over a chat's local photos and videos: swipe to move, pinch and double-tap to zoom photos,
/// system controls for videos, Share and Save.
struct MediaViewerView: View {
    let request: ViewerRequest
    @Environment(\.dismiss) private var dismiss
    @State private var position: String?
    @State private var saveResult: SaveResult?

    private enum SaveResult { case saved, failed }

    init(request: ViewerRequest) {
        self.request = request
        let start = request.items.indices.contains(request.start) ? request.start : 0
        _position = State(initialValue: request.items.indices.contains(start) ? request.items[start].messageID : nil)
    }

    private var current: ChatMedia.ViewerItem? {
        request.items.first { $0.messageID == position } ?? request.items.first
    }

    var body: some View {
        NavigationStack {
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(request.items, id: \.messageID) { item in
                        page(item)
                            .containerRelativeFrame([.horizontal, .vertical])
                            .id(item.messageID)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $position)
            .scrollIndicators(.hidden)
            .background(Color.black.ignoresSafeArea())
            .overlay(alignment: .bottom) { saveBanner }
            .navigationTitle(title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("viewer.done")
                }
                if let current {
                    ToolbarItemGroup(placement: .primaryAction) {
                        ShareLink(item: current.url) { Image(systemName: "square.and.arrow.up") }
                            .accessibilityLabel("Share")
                            .accessibilityIdentifier("viewer.share")
                        Button { Task { await save(current) } } label: { Image(systemName: "square.and.arrow.down") }
                            .accessibilityLabel(Self.saveLabel)
                            .accessibilityIdentifier("viewer.save")
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
        .accessibilityIdentifier("viewer")
    }

    private var title: String {
        guard let current, let index = request.items.firstIndex(where: { $0.messageID == current.messageID }) else { return "" }
        return "\(index + 1) / \(request.items.count)"
    }

    private static var saveLabel: LocalizedStringKey {
        #if os(iOS)
        return "Save to Photos"
        #else
        return "Save"
        #endif
    }

    @ViewBuilder
    private func page(_ item: ChatMedia.ViewerItem) -> some View {
        if item.isVideo {
            ViewerVideoPage(url: item.url, isCurrent: item.messageID == current?.messageID)
        } else {
            ViewerPhotoPage(url: item.url)
        }
    }

    @ViewBuilder
    private var saveBanner: some View {
        if let saveResult {
            (saveResult == .saved ? Text("Saved") : Text("Couldn't save"))
                .font(.callout.weight(.semibold))
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .padding(.bottom, 24)
                .accessibilityIdentifier("viewer.saveResult")
                .task {
                    try? await Task.sleep(for: .seconds(2))
                    self.saveResult = nil
                }
        }
    }

    private func save(_ item: ChatMedia.ViewerItem) async {
        let saved = await MediaSaver.save(url: item.url, isVideo: item.isVideo)
        saveResult = saved ? .saved : .failed
    }
}

/// A photo that can be pinched and double-tapped to zoom, and dragged while zoomed.
private struct ViewerPhotoPage: View {
    let url: URL
    @State private var image: DecodedImage?
    @State private var failed = false
    @State private var scale: CGFloat = 1
    @State private var baseScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var baseOffset: CGSize = .zero

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image.cgImage, scale: 1)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(
                        MagnifyGesture()
                            .onChanged { scale = min(max(baseScale * $0.magnification, 1), 6) }
                            .onEnded { _ in
                                baseScale = scale
                                if scale <= 1 { resetZoom() }
                            })
                    .simultaneousGesture(
                        DragGesture()
                            .onChanged { offset = CGSize(width: baseOffset.width + $0.translation.width, height: baseOffset.height + $0.translation.height) }
                            .onEnded { _ in baseOffset = offset },
                        including: scale > 1 ? .all : .none)
                    .onTapGesture(count: 2) {
                        withAnimation(.snappy) {
                            if scale > 1 { resetZoom() } else { scale = 2.5; baseScale = 2.5 }
                        }
                    }
                    .accessibilityLabel(Text("Photo"))
                    .accessibilityAddTraits(.isImage)
                    .accessibilityHint(Text("Double-tap to zoom"))
            } else if failed {
                Image(systemName: "photo").font(.largeTitle).foregroundStyle(.secondary)
                    .accessibilityLabel(Text("Couldn't open this photo"))
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: url) {
            image = await ThumbnailLoader.shared.image(at: url, maxPixel: 2_400)
            failed = image == nil
        }
    }

    private func resetZoom() {
        scale = 1
        baseScale = 1
        offset = .zero
        baseOffset = .zero
    }
}

/// A video with the system player controls. It pauses when the person swipes to another item.
private struct ViewerVideoPage: View {
    let url: URL
    let isCurrent: Bool
    @State private var player: AVPlayer?

    var body: some View {
        Group {
            if let player {
                VideoPlayer(player: player)
                    .accessibilityLabel(Text("Play video"))
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: url) { player = AVPlayer(url: url) }
        .onChange(of: isCurrent) { _, current in if !current { player?.pause() } }
        .onDisappear { player?.pause() }
    }
}

/// Saves a photo or video: to the photo library on iPhone, to a file the person chooses on the Mac.
enum MediaSaver {
    @MainActor
    static func save(url: URL, isVideo: Bool) async -> Bool {
        #if os(iOS)
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return false }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                if isVideo {
                    _ = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                } else {
                    _ = PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: url)
                }
            }
            return true
        } catch { return false }
        #else
        let panel = NSSavePanel()
        panel.nameFieldStringValue = (isVideo ? "Video." : "Photo.") + (url.pathExtension.isEmpty ? (isVideo ? "mov" : "jpg") : url.pathExtension)
        guard panel.runModal() == .OK, let destination = panel.url else { return false }
        do {
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.copyItem(at: url, to: destination)
            return true
        } catch { return false }
        #endif
    }
}
