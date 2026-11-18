import AppCore
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

/// The composer's side of link previews: the state, the debounced fetch, and the card with its remove control.
/// Only the sender's app ever fetches, and only while Settings > Privacy > Link previews is on.
@MainActor @Observable
final class LinkPreviewComposerModel {
    private(set) var draft = LinkPreviewDraft()
    @ObservationIgnored private var task: Task<Void, Never>?

    /// A person pauses this long after the link before the website is contacted, so half-typed addresses are not.
    static let pause: Duration = .seconds(1)

    func draftChanged(_ text: String, enabled: Bool, fetcher: LinkPreviewFetcher) {
        if let url = draft.textChanged(text, enabled: enabled) {
            task?.cancel()
            task = Task { [weak self] in
                try? await Task.sleep(for: Self.pause)
                guard !Task.isCancelled else { return }
                let card = await fetcher.preview(for: url)
                guard !Task.isCancelled else { return }
                self?.draft.fetched(card, for: url)
            }
        } else if draft.loading == nil {
            task?.cancel()
        }
    }

    func remove() {
        task?.cancel()
        draft.remove()
    }

    func reset() {
        task?.cancel()
        draft.reset()
    }

    func cardToSend(for text: String) -> LinkPreviewCard? { draft.cardToSend(for: text) }
}

/// Puts the preview card, when there is one, above the composer.
struct ComposerWithPreview<Content: View>: View {
    let previews: LinkPreviewComposerModel
    @ViewBuilder let composer: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            if let card = previews.draft.card {
                HStack(alignment: .top, spacing: 4) {
                    LinkPreviewCardView(card: card, isMine: false, opensLink: false)
                    Button { previews.remove() } label: {
                        Image(systemName: "xmark.circle.fill").font(.title3).foregroundStyle(.secondary)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Remove preview")
                    .accessibilityIdentifier("chat.linkpreview.remove")
                }
                .padding(.horizontal, 14)
                .padding(.top, 6)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("chat.linkpreview.composer")
            }
            composer()
        }
    }
}

/// A preview as the message carries it. Shows only what is in the message: nothing here fetches anything. A tap opens the
/// link in the system browser (https only).
struct LinkPreviewCardView: View {
    let card: LinkPreviewCard
    let isMine: Bool
    var opensLink = true

    private var url: URL? {
        guard let url = URL(string: card.url), url.scheme?.lowercased() == "https", url.host?.isEmpty == false else { return nil }
        return url
    }

    var body: some View {
        if opensLink, let url {
            Link(destination: url) { cardBody }
                .buttonStyle(.plain)
        } else {
            cardBody
        }
    }

    private var cardBody: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let data = card.image, let image = Self.decode(data) {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: 120)
                    .clipped()
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(card.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                if let desc = card.desc, !desc.isEmpty {
                    Text(desc).font(.footnote).lineLimit(3)
                }
                if let host = url?.host {
                    Text(host).font(.caption).opacity(0.7).lineLimit(1)
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 6)
            .padding(.top, card.image == nil ? 6 : 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(isMine ? 0 : 0.06), in: RoundedRectangle(cornerRadius: 12))
        .background(isMine ? Color.white.opacity(0.18) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("Link preview"))
        .accessibilityValue(Text(card.title))
        .accessibilityIdentifier("chat.linkpreview.card")
    }

    private static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}

/// The page fetcher the app uses. In `-sampleData` mode a canned page answers every address (the rules still run).
extension LinkPreviewFetcher {
    static var live: LinkPreviewFetcher {
        LinkPreviewFetcher(transport: URLSessionPreviewTransport(), resolver: SystemPreviewResolver(), shrink: PreviewImageShrinker.shrink)
    }

    static var sample: LinkPreviewFetcher {
        LinkPreviewFetcher(transport: SampleLinkPreviewTransport(), resolver: SampleLinkPreviewResolver(), shrink: { _ in nil })
    }
}

private struct SampleLinkPreviewTransport: PreviewTransport {
    func get(_ url: URL, accept: String, maxBytes: Int) async throws -> PreviewResponse {
        let page = "<title>Example Page</title><meta property=\"og:description\" content=\"A sample description of the page.\">"
        return PreviewResponse(status: 200, contentType: "text/html", body: Data(page.utf8))
    }
}

private struct SampleLinkPreviewResolver: PreviewResolver {
    func addresses(for host: String) async -> [String] { ["93.184.216.34"] }
}

/// Re-encodes a downloaded picture as a small JPEG with the system encoder (ImageIO), at most 8,192 bytes; `nil` drops it.
/// Metadata is not carried over, and the picture is decoded at thumbnail size only.
enum PreviewImageShrinker {
    static func shrink(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) >= 1 else { return nil }
        return PreviewImageBudget.fit { side, quality in
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: side,
                kCGImageSourceCreateThumbnailWithTransform: true,
            ]
            guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
            let output = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
            CGImageDestinationAddImage(destination, thumbnail, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
            return CGImageDestinationFinalize(destination) ? output as Data : nil
        }
    }
}

/// The Link previews switch is kept on the device (off until the person turns it on). Sample data keeps it in memory only.
enum LinkPreviewSetting {
    private static let key = "settings.linkPreviews"
    static func load() -> Bool { UserDefaults.standard.bool(forKey: key) }
    static func save(_ on: Bool) { UserDefaults.standard.set(on, forKey: key) }
}

extension AppModel {
    /// Only the sender's app builds previews, and only when the switch is on.
    var linkPreviewFetcher: LinkPreviewFetcher { isSampleData ? .sample : .live }
}
