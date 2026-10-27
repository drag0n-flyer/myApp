import AppCore
import Foundation

/// What the extension puts on the notification, decided from a `NotificationOutcome`.
public enum NotificationPresentation: Equatable, Sendable {
    /// `threadID` is nil for the generic fallback.
    case content(title: String, body: String, threadID: String?, sound: Bool)
    /// An empty notification (the system shows nothing). Only with `NSE_FILTERING`.
    case empty
    /// Plan 8's call branch fills the content.
    case call(CallSignalPayload, from: Contact)

    /// `filtering` is true when the extension carries the filtering entitlement. Without it iOS
    /// requires a visible notification, so `drop` shows the generic fallback instead.
    public init(outcome: NotificationOutcome, filtering: Bool, strings: NotificationStrings) {
        switch outcome {
        case .show(let title, let body, let threadID):
            self = .content(title: title, body: body, threadID: threadID, sound: true)
        case .drop:
            self = filtering ? .empty : .fallback(strings)
        case .fallback:
            self = .fallback(strings)
        case .incomingCall(let payload, let contact):
            self = .call(payload, from: contact)
        }
    }

    public static func fallback(_ strings: NotificationStrings) -> NotificationPresentation {
        .content(title: strings.fallbackTitle, body: strings.fallbackBody, threadID: nil, sound: true)
    }
}
