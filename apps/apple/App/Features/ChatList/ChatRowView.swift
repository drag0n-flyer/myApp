import AppCore
import SwiftUI

struct ChatRowView: View {
    let chat: Chat
    let name: String
    /// The other person is typing: "typing…" takes the place of the preview.
    var isTyping = false
    /// A group's own typing line ("Ana is typing…"); `nil` gives "typing…".
    var typingText: String?
    /// The name in front of a group's last message ("You", or a member's name); `nil` for none.
    var previewSender: String?
    @Environment(\.dynamicTypeSize) private var typeSize

    private var time: String { chat.lastMessage.map { TimeLabel.format($0.sentAt) } ?? "" }
    private var preview: String { chat.lastMessage?.previewText ?? "" }
    private var draftLine: String? { ChatListOrder.draftPreview(chat.draft) }

    var body: some View {
        HStack(alignment: .center, spacing: 11) {
            Avatar(name: name, colorKey: chat.contactID ?? chat.id)
            VStack(alignment: .leading, spacing: 2) {
                let nameLayout = typeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
                    : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
                nameLayout {
                    Text(name)
                        .font(.headline)
                        .lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
                    if !typeSize.isAccessibilitySize { Spacer(minLength: 6) }
                    HStack(spacing: 4) {
                        if chat.pinnedAt != nil { Image(systemName: "pin.fill") }
                        if chat.muted { Image(systemName: "bell.slash.fill") }
                        Text(time)
                            .foregroundStyle(chat.unreadCount > 0 && !chat.muted ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize()
                }
                HStack(alignment: .center) {
                    if isTyping {
                        Group {
                            if let typingText { Text(verbatim: typingText) } else { Text("typing…") }
                        }
                        .font(.subheadline).foregroundStyle(.tint)
                        .lineLimit(1)
                    } else if let draftLine {
                        HStack(spacing: 4) {
                            Text("Draft:").foregroundStyle(.red)
                            Text(draftLine).foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                        .lineLimit(typeSize.isAccessibilitySize ? 2 : 1)
                    } else {
                        PreviewText(message: chat.lastMessage, sender: previewSender)
                            .lineLimit(typeSize.isAccessibilitySize ? 2 : 1)
                    }
                    Spacer(minLength: 6)
                    if chat.unreadCount > 0 {
                        Text("\(chat.unreadCount)")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .frame(minWidth: 20, minHeight: 20)
                            .background(Capsule().fill(chat.muted ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint)))
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
        var parts = [name]
        if chat.pinnedAt != nil { parts.append(String(localized: "Pinned")) }
        if chat.muted { parts.append(String(localized: "Muted")) }
        if isTyping { parts.append(typingText ?? String(localized: "typing…")) } else if let draftLine { parts.append(String(localized: "Draft:") + " " + draftLine) } else {
            parts.append(chat.lastMessage?.fromBlockedSender == true ? String(localized: "Message from a blocked contact")
                         : previewSender.map { String(localized: "\($0):") + " " + preview } ?? preview)
        }
        parts.append(time)
        if chat.unreadCount > 0 { parts.append(String(localized: "\(chat.unreadCount) unread")) }
        return Text(parts.filter { !$0.isEmpty }.joined(separator: ", "))
    }
}

/// Last-message preview with an SF Symbol for voice, media, files, and calls.
struct PreviewText: View {
    let message: Message?
    /// A group chat's sender name, shown before the text ("Ana: Lunch?"); `nil` for none.
    var sender: String?

    var body: some View {
        HStack(spacing: 4) {
            if message?.fromBlockedSender == true {
                // Nothing of a blocked member's message shows in the list.
                Text("Message from a blocked contact")
            } else {
                if let sender { Text("\(sender):") }
                if let symbol {
                    Image(systemName: symbol.name).foregroundStyle(symbol.color)
                }
                previewLabel
            }
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
