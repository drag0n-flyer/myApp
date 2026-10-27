import AppCore
import SwiftUI

struct ChatRowView: View {
    let chat: Chat
    let name: String
    @Environment(\.dynamicTypeSize) private var typeSize

    private var time: String { chat.lastMessage.map { TimeLabel.format($0.sentAt) } ?? "" }
    private var preview: String { chat.lastMessage?.previewText ?? "" }

    var body: some View {
        HStack(alignment: .center, spacing: 11) {
            Avatar(name: name)
            VStack(alignment: .leading, spacing: 2) {
                let nameLayout = typeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
                    : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
                nameLayout {
                    Text(name)
                        .font(.headline)
                        .lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
                    if !typeSize.isAccessibilitySize { Spacer(minLength: 6) }
                    Text(time)
                        .font(.caption)
                        .foregroundStyle(chat.unreadCount > 0 ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                        .fixedSize()
                }
                HStack(alignment: .center) {
                    PreviewText(message: chat.lastMessage)
                        .lineLimit(typeSize.isAccessibilitySize ? 2 : 1)
                    Spacer(minLength: 6)
                    if chat.unreadCount > 0 {
                        Text("\(chat.unreadCount)")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .frame(minWidth: 20, minHeight: 20)
                            .background(Capsule().fill(.tint))
                            .fixedSize()
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: Text {
        var parts = [name, preview, time]
        if chat.unreadCount > 0 { parts.append(String(localized: "\(chat.unreadCount) unread")) }
        return Text(parts.filter { !$0.isEmpty }.joined(separator: ", "))
    }
}

/// Last-message preview with an SF Symbol for voice, media, files, and calls.
struct PreviewText: View {
    let message: Message?

    var body: some View {
        HStack(spacing: 4) {
            if let symbol {
                Image(systemName: symbol.name).foregroundStyle(symbol.color)
            }
            previewLabel
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
    }

    /// Localized text for the content kinds that carry fixed wording; everything else uses `previewText`.
    @ViewBuilder
    private var previewLabel: some View {
        switch message?.content {
        case .deleted: Text("This message was deleted").italic()
        case .unsupported: Text("Update \(AppBrand.name) to see this message")
        case .system(let event): SystemEventText(event: event)
        default: Text(message?.previewText ?? "")
        }
    }

    private var symbol: (name: String, color: Color)? {
        switch message?.content {
        case .voice: ("mic.fill", .secondary)
        case .photo: ("camera.fill", .secondary)
        case .file: ("doc.fill", .secondary)
        case .call(.missedAudio), .call(.missedVideo): ("phone.arrow.down.left", .red)
        case .call(.audio): ("phone.fill", .secondary)
        case .call(.video): ("video.fill", .secondary)
        case .text, .deleted, .unsupported, .system, nil: nil
        }
    }
}
