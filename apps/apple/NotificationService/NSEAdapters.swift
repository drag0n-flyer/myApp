import Foundation
import LocalStore
import Messaging
import SecureMessaging

/// What the notification extension builds on top of `MessagingDeps`: the same `IncomingHandler`,
/// `InboxProcessor` and `ConversationManager` the app uses, so a push is processed exactly like an inbox item.
enum NSEAdapters {
    struct Adapters {
        let handler: any IncomingApplying
        let inbox: any InboxSyncing
        let conversations: ConversationManaging
    }

    /// The real MLS engine, reading its group state from the shared container on each call (the app and the
    /// extension change it from two processes). nil when this build has no engine: every push then shows the
    /// generic notice. It never falls back to `FakeMLSEngine`: that has no cryptography.
    static func makeEngine(container: URL, accountID: String, deviceID: String) -> MLSEngine? {
        #if canImport(MLSFFIBindings)
        return try? makeMLSEngine(identity: "\(accountID)/\(deviceID)", storage: MessagingLocations.mlsURL(in: container))
        #else
        return nil
        #endif
    }

    /// The extension never sends: the inbox processor has no control sender (it leaves receipt markers for the
    /// app) and the conversation manager is only needed as a port.
    static func make(deps: MessagingDeps) -> Adapters? {
        let keys = EscrowKeyProvider(server: deps.server)
        let conversations = ConversationManager(deps: deps, escrowKey: keys.closure)
        let handler = IncomingHandler(me: deps.me, now: deps.now)
        let inbox = InboxProcessor(deps: deps, conversations: conversations, handler: handler, controls: nil)
        return Adapters(handler: handler, inbox: inbox, conversations: conversations)
    }
}
