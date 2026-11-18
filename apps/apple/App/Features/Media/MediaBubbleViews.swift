import AppCore
import SwiftUI

/// Photo or file inside a bubble, for every state of `MediaBubbleState`.
struct MediaBubbleView: View {
    @ScaledMetric(relativeTo: .body) private var mediaWidth: CGFloat = 220
    @ScaledMetric(relativeTo: .body) private var mediaHeight: CGFloat = 160
    let isPhoto: Bool
    /// The cleaned name for a file; nil for a photo.
    let name: String?
    let bytes: Int64
    let state: MediaBubbleState
    let messageID: String
    var canDownload = true
    let download: () -> Void
    let open: (URL) -> Void

    private var label: String {
        isPhoto ? String(localized: "Photo · \(DataFormat.size(bytes: bytes))") : "\(name ?? MediaFileName.fallback) · \(DataFormat.size(bytes: bytes))"
    }

    var body: some View {
        switch state {
        case .ready(let url):
            Button { open(url) } label: {
                if isPhoto {
                    PhotoThumbnail(url: url)
                } else {
                    Label(label, systemImage: "doc.fill")
                        .font(.body)
                        .lineLimit(2)
                        .frame(maxWidth: mediaWidth, alignment: .leading)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(isPhoto ? String(localized: "Photo") : label))
            .accessibilityHint(Text("Opens it"))
            .accessibilityIdentifier("chat.media.open.\(messageID)")
        case .notDownloaded where !canDownload:
            // Nothing to fetch (sample data): a plain placeholder with the size, not a button.
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.secondary.opacity(0.25))
                .frame(width: mediaWidth, height: isPhoto ? mediaHeight : 80)
                .overlay {
                    VStack(spacing: 6) {
                        Image(systemName: isPhoto ? "photo" : "doc.fill").font(.title)
                        Text(label).font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(label))
                .accessibilityIdentifier("chat.media.placeholder.\(messageID)")
        case .notDownloaded:
            placeholder {
                Image(systemName: "arrow.down")
                    .font(.headline)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(.black.opacity(0.35)))
                Text(label).font(.caption.weight(.semibold))
            }
            .accessibilityLabel(Text("Download"))
            .accessibilityValue(Text(label))
            .accessibilityIdentifier("chat.media.download.\(messageID)")
        case .downloading(let fraction):
            VStack(spacing: 6) {
                ProgressView(value: fraction).frame(width: 120)
                Text(label).font(.caption.weight(.semibold))
            }
            .frame(width: mediaWidth, height: isPhoto ? mediaHeight : nil)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(label))
            .accessibilityValue(Text(fraction, format: .percent.precision(.fractionLength(0))))
            .accessibilityIdentifier("chat.media.progress.\(messageID)")
        case .failed:
            placeholder {
                Image(systemName: "exclamationmark.arrow.triangle.2.circlepath")
                    .font(.headline)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(.black.opacity(0.35)))
                Text("Couldn't download").font(.caption.weight(.semibold))
                Text("Try again").font(.caption).underline()
            }
            .accessibilityLabel(Text("Couldn't download"))
            .accessibilityHint(Text("Downloads it again"))
            .accessibilityIdentifier("chat.media.retry.\(messageID)")
        }
    }

    /// A blurred grey block with centred content that starts the download when tapped.
    private func placeholder<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        Button(action: download) {
            RoundedRectangle(cornerRadius: 12)
                .fill(LinearGradient(colors: [.gray.opacity(0.5), .gray.opacity(0.3)], startPoint: .top, endPoint: .bottom))
                .frame(width: mediaWidth, height: isPhoto ? mediaHeight : 80)
                .overlay {
                    VStack(spacing: 6) { content() }
                        .foregroundStyle(.white)
                        .padding(6)
                }
        }
        .buttonStyle(.plain)
    }
}

/// A voice message: play or pause, progress and elapsed time while it plays, and the download states.
struct VoiceMessageView: View {
    let messageID: String
    let duration: Double
    let isMine: Bool
    let state: MediaBubbleState
    let player: VoicePlayer?
    var canDownload = true
    let download: () -> Void

    private let bars: [CGFloat] = [6, 12, 18, 9, 15, 7, 13, 17, 8, 11, 5, 14]

    private var isCurrent: Bool { player?.playingMessageID == messageID }
    private var progress: Double { isCurrent ? (player?.progress ?? 0) : 0 }
    private var failedToPlay: Bool { player?.failedMessageIDs.contains(messageID) == true }

    var body: some View {
        HStack(spacing: 7) {
            button
            if failedToPlay || state == .failed {
                Text("Couldn't download").font(.caption)
            } else if isCurrent {
                ProgressView(value: progress)
                    .frame(width: 96)
                    .tint(isMine ? Color.white : Color.accentColor)
                Text(VoiceSelection.elapsedText(progress: progress, duration: duration)).font(.caption.monospacedDigit()).opacity(0.7)
            } else {
                HStack(spacing: 2) {
                    ForEach(bars.indices, id: \.self) { i in
                        Capsule().frame(width: 2.5, height: bars[i]).opacity(0.75)
                    }
                }
                .accessibilityHidden(true)
                Text(Message.clockText(duration)).font(.caption).opacity(0.7)
            }
        }
    }

    @ViewBuilder
    private var button: some View {
        switch state {
        case .ready(let url):
            Button {
                player?.toggle(messageID: messageID, url: url, isMine: isMine)
            } label: {
                circle(systemName: player?.isPlaying(messageID) == true ? "pause.fill" : "play.fill")
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player?.isPlaying(messageID) == true ? Text("Pause") : Text("Play voice message"))
            .accessibilityValue(Text(Message.clockText(duration)))
            .modifier(CallHint(active: player?.callActive == true))
            .accessibilityIdentifier("chat.voice.play.\(messageID)")
        case .notDownloaded where !canDownload:
            circle(systemName: "play.fill")
                .opacity(0.5)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("Play voice message"))
                .accessibilityValue(Text(Message.clockText(duration)))
        case .notDownloaded, .failed:
            Button(action: download) { circle(systemName: state == .failed ? "arrow.clockwise" : "arrow.down") }
                .buttonStyle(.plain)
                .accessibilityLabel(state == .failed ? Text("Try again") : Text("Download"))
                .accessibilityValue(Text(Message.clockText(duration)))
                .accessibilityIdentifier("chat.voice.download.\(messageID)")
        case .downloading(let fraction):
            ProgressView(value: fraction)
                .progressViewStyle(.circular)
                .frame(width: 28, height: 28)
                .accessibilityLabel(Text("Voice message, \(Message.clockText(duration))"))
                .accessibilityValue(Text(fraction, format: .percent.precision(.fractionLength(0))))
        }
    }

    private func circle(systemName: String) -> some View {
        Image(systemName: systemName)
            .font(.caption)
            .frame(width: 28, height: 28)
            .background(Circle().fill(isMine ? Color.white.opacity(0.25) : Color.accentColor))
            .foregroundStyle(.white)
    }
}

/// While a call is on the play button is dimmed and says why; otherwise it carries no hint at all.
private struct CallHint: ViewModifier {
    let active: Bool

    func body(content: Content) -> some View {
        if active {
            content.opacity(0.5).accessibilityHint(Text("You can play this after the call."))
        } else {
            content
        }
    }
}
