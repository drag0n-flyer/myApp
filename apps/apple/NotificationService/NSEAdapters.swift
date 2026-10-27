import Foundation
import LocalStore
import Messaging
import SecureMessaging

/// MERGE POINT (I1). The pieces below belong to other lanes and are not on this branch yet. When
/// they merge, replace the bodies marked `MERGE(I1)` and nothing else in the extension:
///
/// - `makeEngine`: lane M5's real MLS engine, reading group state from the shared container on each
///   call (the app and the extension change it from two processes). Until it exists this returns
///   nil, so every push shows the generic fallback.
/// - `make(deps:)`: lane A's `IncomingHandler` as `IncomingApplying`, its `InboxProcessor` as
///   `InboxSyncing`, and its `ConversationManager` as `ConversationManaging`. The notification
///   processor only needs the narrow ports from `Notifications/NotificationProcessor.swift`.
enum NSEAdapters {
    struct Adapters {
        let handler: any IncomingApplying
        let inbox: any InboxSyncing
        let conversations: ConversationManaging
    }

    /// MERGE(I1): return the real engine (`makeMLSEngine(...)`, SecureMessaging).
    static func makeEngine(container: URL, accountID: String, deviceID: String) -> MLSEngine? {
        nil
    }

    /// MERGE(I1): build the three ports from lane A's types using `deps`.
    static func make(deps: MessagingDeps) -> Adapters? {
        nil
    }
}
