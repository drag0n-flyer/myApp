import AppCore
import SwiftUI

struct MessageBubbleView: View {
    let message: Message
    let isMine: Bool
    let reply: Message?
    let replySenderName: (String) -> String
    /// What the photo, file or voice message shows (from `message.media`).
    let mediaState: MediaBubbleState
    let download: () -> Void
    /// Opens a downloaded photo or file: the file, its received name (nil for photos) and whether it is a photo.
    var openMedia: (URL, String?, Bool) -> Void = { _, _, _ in }
    /// Stops an upload that is still running and removes its message.
    var cancelUpload: () -> Void = {}
    var voice: VoicePlayer?
    /// False with sample data: there is nothing to fetch, so the bubble shows a plain placeholder, not a Download button.
    var canDownload = true
    /// Sends a message that is marked "Not sent" again.
    var retry: () -> Void = {}
    /// Context menu items for this message, from `MessageActions.available`.
    var actions: [MessageAction] = []
    var perform: (MessageAction) -> Void = { _ in }
    var react: (String) -> Void = { _ in }
    /// Briefly true after a jump to this message.
    var isHighlighted = false
    /// Jumps to the quoted message.
    var quoteTapped: () -> Void = {}

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
                if reply != nil || message.replyToID != nil {
                    quote
                }
                content
                MetaLine(message: message, isMine: isMine, retry: retry)
            }
            .padding(.horizontal, 10)
            .padding(.top, 7)
            .padding(.bottom, 5)
            .foregroundStyle(isMine ? Color.white : Color.primary)
            .background(bubbleBackground, in: bubbleShape)
            .overlay {
                // Accent on your own accent-coloured bubble would not show.
                bubbleShape.stroke(isMine ? Color.primary : Color.accentColor, lineWidth: 3).opacity(isHighlighted ? 1 : 0)
            }
            .animation(.easeInOut(duration: 0.3), value: isHighlighted)
            .contextMenu { menu }
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

    /// Detected links, underlined so they look like links on both bubble colours.
    private static func underlinedLinks(_ text: String) -> AttributedString {
        // Formatting is display only: `MessageFormat` styles the spans and keeps links outside monospace.
        var attributed = MessageFormat.attributed(text)
        for run in attributed.runs where run.link != nil {
            attributed[run.range].swiftUI.underlineStyle = .single
        }
        return attributed
    }

    private var bubbleShape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: 18, bottomLeadingRadius: isMine ? 18 : 6,
            bottomTrailingRadius: isMine ? 6 : 18, topTrailingRadius: 18)
    }

    /// The quoted message; tapping it jumps to the original. When the original is not loaded the quote still shows.
    private var quote: some View {
        Button(action: quoteTapped) {
            VStack(alignment: .leading, spacing: 1) {
                if let reply {
                    Text(replySenderName(reply.senderID)).font(.caption.weight(.semibold))
                    Text(reply.previewText).font(.caption).lineLimit(1)
                } else {
                    Text("Earlier message").font(.caption.weight(.semibold))
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.white.opacity(isMine ? 0.18 : 0), in: RoundedRectangle(cornerRadius: 6))
            .overlay(alignment: .leading) {
                Rectangle().fill(isMine ? Color.white.opacity(0.8) : Color.accentColor).frame(width: 3)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Shows the original message")
        .accessibilityIdentifier("chat.quote.\(message.id)")
    }

    @ViewBuilder
    private var menu: some View {
        if actions.contains(.react) {
            ControlGroup {
                ForEach(MessageActions.reactionEmoji, id: \.self) { emoji in
                    Button(emoji) { react(emoji) }
                        .accessibilityLabel(Text("React \(emoji)"))
                }
            }
            .controlGroupStyle(.palette)
        }
        if actions.contains(.reply) {
            Button("Reply", systemImage: "arrowshape.turn.up.left") { perform(.reply) }
        }
        if actions.contains(.copy) {
            Button("Copy", systemImage: "doc.on.doc") { perform(.copy) }
        }
        if actions.contains(.forward) {
            Button("Forward", systemImage: "arrowshape.turn.up.right") { perform(.forward) }
        }
        if actions.contains(.edit) {
            Button("Edit", systemImage: "pencil") { perform(.edit) }
        }
        if actions.contains(.delete) {
            Button("Delete", systemImage: "trash", role: .destructive) { perform(.delete) }
        }
    }

    private var bubbleBackground: AnyShapeStyle {
        isMine ? AnyShapeStyle(Color.accentColor.gradient) : AnyShapeStyle(Color.secondaryBackground)
    }

    @ViewBuilder
    private var content: some View {
        switch message.content {
        case .text(let text):
            Text(Self.underlinedLinks(text)).font(.body).tint(isMine ? Color.white : Color.accentColor)
        case .voice(let duration):
            VoiceMessageView(messageID: message.id, duration: duration, isMine: isMine, state: mediaState, player: voice,
                             canDownload: canDownload, download: download)
        case .photo(let bytes):
            MediaBubbleView(isPhoto: true, uploadProgress: isMine ? message.uploadProgress : nil, cancelUpload: cancelUpload,
                            name: nil, bytes: bytes, state: mediaState, messageID: message.id,
                            canDownload: canDownload, download: download, open: { openMedia($0, nil, true) })
        case .file(let name, let bytes):
            let cleaned = MediaFileName.clean(name)
            MediaBubbleView(isPhoto: false, isVideo: ChatMedia.isVideo(name: cleaned),
                            uploadProgress: isMine ? message.uploadProgress : nil, cancelUpload: cancelUpload,
                            name: cleaned, bytes: bytes, state: mediaState, messageID: message.id,
                            canDownload: canDownload, download: download, open: { openMedia($0, cleaned, false) })
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
        // Placeholder wording until the disappearing-messages screens (P3) add the catalog strings.
        case .timer: Text(verbatim: event.text)
        }
    }
}

private struct MetaLine: View {
    let message: Message
    let isMine: Bool
    let retry: () -> Void

    var body: some View {
        HStack(spacing: 3) {
            Spacer(minLength: 0)
            if isMine, message.status == .failed {
                Label(failedText, systemImage: "exclamationmark.circle.fill")
                    .accessibilityIdentifier("chat.failed.\(message.id)")
                Button("Try again", action: retry)
                    .buttonStyle(.borderless)
                    .underline()
                    .accessibilityLabel(Text("Try again"))
                    .accessibilityHint(Text("Sends this message again"))
                    .accessibilityIdentifier("chat.retry.\(message.id)")
            }
            if isMine, message.status == .sending { Text("Sending…") }
            if message.editedAt != nil { Text("edited") }
            Text(message.sentAt, format: .dateTime.hour().minute())
            if isMine, message.status != .failed {
                Image(systemName: statusSymbol)
                    .accessibilityLabel(statusLabel)
            }
        }
        .font(.caption2)
        .opacity(0.75)
    }

    /// "Not sent", or why a failed attachment will not go as it is.
    private var failedText: LocalizedStringKey {
        guard message.sendFailure == .tooBig else { return "Not sent" }
        if case .file(let name, _) = message.content, ChatMedia.isVideo(name: name) { return "This video is too big to send." }
        return "This file is too big to send."
    }

    /// Sent: a check. Delivered: an outline check circle. Read: a filled one.
    private var statusSymbol: String {
        switch message.status {
        case .sending: "clock"
        case .sent, .failed: "checkmark"
        case .delivered: "checkmark.circle"
        case .read: "checkmark.circle.fill"
        }
    }

    private var statusLabel: Text {
        switch message.status {
        case .sending: Text("Sending…")
        case .sent: Text("Sent")
        case .delivered: Text("Delivered")
        case .read: Text("Read")
        case .failed: Text("Not sent")
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
