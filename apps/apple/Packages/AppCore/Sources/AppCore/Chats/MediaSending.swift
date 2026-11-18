import Foundation

/// One photo or video chosen to send.
public struct PickedMedia: Equatable, Sendable {
    public enum Kind: Equatable, Sendable { case photo, video }
    public var kind: Kind
    public var bytes: Int64

    public init(kind: Kind, bytes: Int64) {
        self.kind = kind
        self.bytes = bytes
    }
}

/// What to send after the preview screen's Send: the picked items in order, the trimmed caption travelling in the
/// first one (spec 6.3; no text message follows it). Pure, so Android and Apple share one test table.
public enum SendPlan {
    public static let maxItems = 10
    /// A caption follows the text rules: at most 4,096 characters (graphemes).
    public static let maxCaption = 4_096
    /// The attachment size limit when the server has not told the app a different one (100 MiB).
    public static let defaultLimitBytes: Int64 = 104_857_600

    public enum Step: Equatable, Sendable {
        /// The picked item at this index, with its caption (only the first item has one).
        case attachment(Int, caption: String?)
    }

    public enum Refusal: Equatable, Sendable { case tooMany, videoTooBig }

    public enum Result: Equatable, Sendable {
        case send([Step])
        case refused(Refusal)
    }

    /// `tooMany` over 10 items; `videoTooBig` when a video is larger than `limitBytes`. Otherwise the attachments in
    /// order; the first carries the trimmed caption (`nil` when blank), cut to `maxCaption` characters.
    public static func plan(items: [PickedMedia], caption: String, limitBytes: Int64) -> Result {
        guard items.count <= maxItems else { return .refused(.tooMany) }
        if items.contains(where: { $0.kind == .video && $0.bytes > limitBytes }) { return .refused(.videoTooBig) }
        var steps: [Step] = []
        let trimmed = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        let text = trimmed.isEmpty ? nil : String(trimmed.prefix(maxCaption))
        for index in items.indices { steps.append(.attachment(index, caption: index == 0 ? text : nil)) }
        return .send(steps)
    }
}

/// Where an outgoing attachment is, for the Cancel control.
public enum UploadPhase: Equatable, Sendable { case queued, uploading, sent }

/// Cancel stops and removes a message that has not been sent; once it is sent, cancel changes nothing.
public enum UploadCancel {
    public enum Action: Equatable, Sendable { case removeUnsent, keep }

    public static func onCancel(_ phase: UploadPhase) -> Action {
        switch phase {
        case .queued, .uploading: .removeUnsent
        case .sent: .keep
        }
    }
}

/// An item of the per-chat gallery.
public struct GalleryItem: Equatable, Sendable {
    public var messageID: String
    public var date: Date

    public init(messageID: String, date: Date) {
        self.messageID = messageID
        self.date = date
    }
}

/// One month of the Media grid.
public struct GalleryMonth: Equatable, Sendable {
    public var year: Int
    public var month: Int
    /// Newest first.
    public var items: [GalleryItem]
}

public enum GalleryGroups {
    /// Groups by calendar month in `zone`, newest month first and newest item first inside a month.
    public static func byMonth(_ items: [GalleryItem], zone: TimeZone) -> [GalleryMonth] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let sorted = items.sorted { $0.date != $1.date ? $0.date > $1.date : $0.messageID > $1.messageID }
        var months: [GalleryMonth] = []
        for item in sorted {
            let parts = calendar.dateComponents([.year, .month], from: item.date)
            let year = parts.year ?? 0, month = parts.month ?? 0
            if let last = months.last, last.year == year, last.month == month {
                months[months.count - 1].items.append(item)
            } else {
                months.append(GalleryMonth(year: year, month: month, items: [item]))
            }
        }
        return months
    }
}
