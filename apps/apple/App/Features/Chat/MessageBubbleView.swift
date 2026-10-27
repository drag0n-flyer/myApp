import AppCore
import SwiftUI

struct MessageBubbleView: View {
    let message: Message
    let isMine: Bool
    let reply: Message?
    let replySenderName: (String) -> String
    let showsMedia: Bool
    let download: () -> Void

    var body: some View {
        if case .system(let event) = message.content {
            SystemEventText(event: event)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
        } else {
            bubble
        }
    }

    private var bubble: some View {
        HStack {
            if isMine { Spacer(minLength: 60) }
            VStack(alignment: .leading, spacing: 4) {
                if let reply {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(replySenderName(reply.senderID)).font(.caption.weight(.semibold))
                        Text(reply.previewText).font(.caption).lineLimit(1)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white.opacity(isMine ? 0.18 : 0), in: RoundedRectangle(cornerRadius: 6))
                    .overlay(alignment: .leading) {
                        Rectangle().fill(isMine ? Color.white.opacity(0.8) : Color.accentColor).frame(width: 3)
                    }
                }
                content
                MetaLine(message: message, isMine: isMine)
            }
            .padding(.horizontal, 10)
            .padding(.top, 7)
            .padding(.bottom, 5)
            .foregroundStyle(isMine ? Color.white : Color.primary)
            .background(bubbleBackground, in: UnevenRoundedRectangle(
                topLeadingRadius: 18, bottomLeadingRadius: isMine ? 18 : 6,
                bottomTrailingRadius: isMine ? 6 : 18, topTrailingRadius: 18))
            .overlay(alignment: .bottomLeading) {
                if !message.reactions.isEmpty {
                    Text(message.reactions.joined())
                        .font(.caption)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Color.secondaryBackground, in: Capsule())
                        .shadow(radius: 1)
                        .offset(x: 8, y: 12)
                        .accessibilityLabel(Text("Reaction \(message.reactions.joined())"))
                }
            }
            .padding(.bottom, message.reactions.isEmpty ? 0 : 10)
            if !isMine { Spacer(minLength: 60) }
        }
    }

    private var bubbleBackground: AnyShapeStyle {
        isMine ? AnyShapeStyle(Color.accentColor.gradient) : AnyShapeStyle(Color.secondaryBackground)
    }

    @ViewBuilder
    private var content: some View {
        switch message.content {
        case .text(let text):
            Text(text).font(.body)
        case .voice(let duration):
            VoiceMessageView(duration: duration, isMine: isMine)
        case .photo(let bytes):
            MediaView(label: String(localized: "Photo · \(DataFormat.size(bytes: bytes))"),
                      symbol: "photo", isShown: showsMedia, identifier: "chat.media.download.\(message.id)", download: download)
        case .file(let name, let bytes):
            MediaView(label: "\(name) · \(DataFormat.size(bytes: bytes))",
                      symbol: "doc.fill", isShown: showsMedia, identifier: "chat.media.download.\(message.id)", download: download)
        case .call:
            Label(message.previewText, systemImage: "phone")
        case .deleted:
            Text("This message was deleted")
                .font(.body.italic())
                .foregroundStyle(isMine ? Color.white.opacity(0.8) : Color.secondary)
        case .unsupported:
            Text("Update \(AppBrand.name) to see this message").font(.body)
        case .system:
            EmptyView()
        }
    }
}

/// The chat line for a device or safety change. Wording lives in the String Catalog.
struct SystemEventText: View {
    let event: SystemEvent

    var body: some View {
        switch event {
        case .deviceAdded(let name?): Text("\(name) added a new device")
        case .deviceAdded(nil): Text("You added a new device")
        case .deviceRemoved(let name?): Text("\(name) removed a device")
        case .deviceRemoved(nil): Text("You removed a device")
        case .safetyNumberChanged: Text("Safety number changed")
        case .messagesPaused: Text("Messages in this chat are paused. Open chat info for details.")
        }
    }
}

private struct MetaLine: View {
    let message: Message
    let isMine: Bool

    var body: some View {
        HStack(spacing: 3) {
            Spacer(minLength: 0)
            Text(message.sentAt, format: .dateTime.hour().minute())
            if isMine {
                Image(systemName: message.status == .sent || message.status == .sending ? "checkmark" : "checkmark.circle.fill")
                    .accessibilityLabel(statusLabel)
            }
        }
        .font(.caption2)
        .opacity(0.75)
    }

    private var statusLabel: Text {
        switch message.status {
        case .sending: Text("Sending")
        case .sent: Text("Sent")
        case .delivered: Text("Delivered")
        case .read: Text("Read")
        }
    }
}

private struct VoiceMessageView: View {
    let duration: Double
    let isMine: Bool
    private let bars: [CGFloat] = [6, 12, 18, 9, 15, 7, 13, 17, 8, 11, 5, 14]

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "play.fill")
                .font(.caption)
                .frame(width: 28, height: 28)
                .background(Circle().fill(isMine ? Color.white.opacity(0.25) : Color.accentColor))
                .foregroundStyle(.white)
            HStack(spacing: 2) {
                ForEach(bars.indices, id: \.self) { i in
                    Capsule().frame(width: 2.5, height: bars[i]).opacity(0.75)
                }
            }
            Text(Message.clockText(duration)).font(.caption).opacity(0.7)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Voice message, \(Message.clockText(duration))"))
        .accessibilityAddTraits(.isButton)
    }
}

/// Photo or file: a blurred placeholder with its size until downloaded.
private struct MediaView: View {
    @ScaledMetric(relativeTo: .body) private var mediaWidth: CGFloat = 200
    @ScaledMetric(relativeTo: .body) private var mediaHeight: CGFloat = 130
    let label: String
    let symbol: String
    let isShown: Bool
    let identifier: String
    let download: () -> Void

    var body: some View {
        if isShown {
            RoundedRectangle(cornerRadius: 12)
                .fill(LinearGradient(colors: [.blue.opacity(0.5), .purple.opacity(0.4), .orange.opacity(0.4)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: mediaWidth).frame(minHeight: mediaHeight)
                .overlay { Image(systemName: symbol).font(.largeTitle).foregroundStyle(.white.opacity(0.8)) }
                .accessibilityLabel(Text(label))
        } else {
            Button(action: download) {
                RoundedRectangle(cornerRadius: 12)
                    .fill(LinearGradient(colors: [.gray.opacity(0.5), .gray.opacity(0.3)], startPoint: .top, endPoint: .bottom))
                    .frame(width: mediaWidth).frame(minHeight: mediaHeight)
                    .blur(radius: 2)
                    .overlay {
                        VStack(spacing: 6) {
                            Image(systemName: "arrow.down")
                                .font(.headline)
                                .frame(width: 38, height: 38)
                                .background(Circle().fill(.black.opacity(0.35)))
                            Text(label).font(.caption.weight(.semibold))
                        }
                        .foregroundStyle(.white)
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(label))
            .accessibilityHint("Downloads")
            .accessibilityIdentifier(identifier)
        }
    }
}

extension Message {
    /// "0:14"
    static func clockText(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        return "\(s / 60):" + (s % 60 < 10 ? "0" : "") + "\(s % 60)"
    }
}
