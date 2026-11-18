import Foundation

/// The composer's preview of the first link of the text being typed (spec 6.3), as a pure state. It decides when a
/// fetch may start and which card may go with the message; it does no network work itself.
///
/// - A fetch starts only while the Link previews switch is on and the text has an https link that is not removed.
/// - A removed link stays removed for this draft, whatever is typed after, until the draft is over (`reset`).
/// - The card goes with the message only while its link is still the first link of the text.
public struct LinkPreviewDraft: Equatable, Sendable {
    public private(set) var card: LinkPreviewCard?
    /// The link being fetched now.
    public private(set) var loading: URL?
    private var removed: Set<String> = []

    public init() {}

    /// The text changed. Returns the link to fetch when one must start now.
    @discardableResult
    public mutating func textChanged(_ text: String, enabled: Bool) -> URL? {
        guard enabled else { clearCurrent(); return nil }
        guard let link = LinkPreviewRule.firstLink(text) else {
            clearCurrent()
            // An empty field ends the draft, so a removed preview may come back for the next one.
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { removed = [] }
            return nil
        }
        let key = link.absoluteString
        if removed.contains(key) { clearCurrent(); return nil }
        if card?.url == key || loading?.absoluteString == key { return nil }
        card = nil
        loading = link
        return link
    }

    /// A fetch ended. A result for a link that is no longer the one wanted is ignored.
    public mutating func fetched(_ result: LinkPreviewCard?, for url: URL) {
        guard loading == url else { return }
        loading = nil
        card = result
    }

    /// The remove (×) control: this link gets no preview for this draft.
    public mutating func remove() {
        if let key = card?.url ?? loading?.absoluteString { removed.insert(key) }
        clearCurrent()
    }

    /// The draft was sent or cleared.
    public mutating func reset() {
        clearCurrent()
        removed = []
    }

    /// The card to send with `text`: only when it belongs to the first link of exactly this text.
    public func cardToSend(for text: String) -> LinkPreviewCard? {
        guard let card, let link = LinkPreviewRule.firstLink(text), link.absoluteString == card.url, text.contains(card.url) else { return nil }
        return card
    }

    private mutating func clearCurrent() {
        card = nil
        loading = nil
    }
}
