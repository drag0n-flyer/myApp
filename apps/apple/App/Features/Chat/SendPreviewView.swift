import AppCore
import SwiftUI

/// A photo or video waiting on the preview screen. `url` is nil for the stand-in used with sample data.
struct DraftMedia: Identifiable, Equatable {
    let id = UUID()
    let url: URL?
    let kind: PickedMedia.Kind
    let bytes: Int64
}

/// After choosing photos or videos, or taking a photo: look at them, add a caption, then Send. Nothing is handed to
/// the engine before Send.
struct SendPreviewView: View {
    let items: [DraftMedia]
    let limitBytes: Int64
    /// Called with the caption when the person taps Send and the plan accepts the items.
    let send: (String) -> Void
    let cancel: () -> Void

    @State private var caption = ""
    @State private var page = 0
    @State private var refusal: SendPlan.Refusal?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                pager
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                TextField("Add a caption", text: $caption, axis: .vertical)
                    .lineLimit(1...4)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .glassBackground(in: RoundedRectangle(cornerRadius: 21))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .accessibilityIdentifier("media.preview.caption")
            }
            .navigationTitle(items.count > 1 ? "\(page + 1) / \(items.count)" : "")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: cancel).accessibilityIdentifier("media.preview.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send", action: trySend).accessibilityIdentifier("media.preview.send")
                }
            }
            .alert(refusalTitle, isPresented: Binding(get: { refusal != nil }, set: { if !$0 { refusal = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 420)
        #endif
        .interactiveDismissDisabled()
        .accessibilityIdentifier("media.preview")
    }

    @ViewBuilder
    private var pager: some View {
        #if os(iOS)
        TabView(selection: $page) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                PreviewPage(item: item).tag(index)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: items.count > 1 ? .automatic : .never))
        #else
        HStack {
            if items.count > 1 {
                Button { page = max(page - 1, 0) } label: { Image(systemName: "chevron.backward") }
                    .accessibilityLabel("Previous")
                    .disabled(page == 0)
            }
            PreviewPage(item: items[min(page, items.count - 1)])
            if items.count > 1 {
                Button { page = min(page + 1, items.count - 1) } label: { Image(systemName: "chevron.forward") }
                    .accessibilityLabel("Next")
                    .disabled(page >= items.count - 1)
            }
        }
        .padding(.horizontal, 8)
        #endif
    }

    private var refusalTitle: LocalizedStringKey {
        refusal == .tooMany ? "You can send up to 10 at a time." : "This video is too big to send."
    }

    private func trySend() {
        let picked = items.map { PickedMedia(kind: $0.kind, bytes: $0.bytes) }
        switch SendPlan.plan(items: picked, caption: caption, limitBytes: limitBytes) {
        case .refused(let reason): refusal = reason
        case .send: send(caption)
        }
    }
}

/// One item of the preview: the picture, or a video's first frame with a play glyph.
private struct PreviewPage: View {
    let item: DraftMedia
    @Environment(\.displayScale) private var displayScale
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: displayScale).resizable().scaledToFit()
            } else {
                Image(systemName: item.kind == .video ? "video" : "photo").font(.system(size: 56)).foregroundStyle(.secondary)
            }
            if item.kind == .video, image != nil {
                Image(systemName: "play.fill")
                    .font(.title)
                    .foregroundStyle(.white)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(.black.opacity(0.45)))
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.kind == .video ? Text("Video") : Text("Photo"))
        .task(id: item.id) {
            guard let url = item.url else { return }
            let pixels = 1_600
            if item.kind == .video {
                image = await VideoPoster.shared.info(at: url, maxPixel: pixels).image
            } else {
                image = await ThumbnailLoader.shared.image(at: url, maxPixel: pixels)?.cgImage
            }
        }
    }
}
