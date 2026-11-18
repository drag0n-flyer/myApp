import AppCore
import Foundation

extension ContentEnvelope {
    /// What an edit with `text` makes of this message, for the sender and the receiver alike (one rule, so the two
    /// sides never differ). A text keeps its preview only while the link is still in the new text. A photo or file
    /// takes `text` as its new caption (trimmed, at most 4,096 characters; blank removes it). `nil` when the edit does
    /// not apply: a blank text, or a kind that cannot be edited.
    /// - Returns: the new envelope, its search text, and the text the `edit` message carries.
    func edited(text: String, fold: (String) -> String) -> (envelope: ContentEnvelope, folded: String, wireText: String)? {
        switch body {
        case .text(_, let reply):
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            let kept = preview.flatMap { text.contains($0.url) ? $0 : nil }
            return (ContentEnvelope(v: v, id: id, at: at, body: .text(text, replyTo: reply), ttl: ttl, preview: kept), fold(text), text)
        case .photo, .file:
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            let caption = trimmed.isEmpty ? nil : String(trimmed.prefix(Self.maxCaptionLength))
            var copy = self
            copy.caption = caption
            var searchable = caption ?? ""
            if case .file(_, let name, _, _) = body { searchable = [name, caption].compactMap { $0 }.joined(separator: " ") }
            return (copy, fold(searchable), caption ?? "")
        default:
            return nil
        }
    }
}
