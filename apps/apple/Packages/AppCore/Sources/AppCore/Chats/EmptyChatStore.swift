import Foundation

/// A live account's store while the real one is not there (before sign-in, while it is being built, or when it
/// could not be built). It holds nothing and keeps nothing: a live account never sees sample chats.
@MainActor
public final class EmptyChatStore: ChatStore {
    public var onChange: (@MainActor () -> Void)?
    public let me = Contact(id: "", displayName: "", username: "")
    public var contacts: [Contact] { [] }
    public var folders: [ChatFolder] { [.all, .unread] }
    public var messageRequests: [Chat] { [] }
    public var messageRequestsRowTitle: String? { nil }
    public var blockedIDs: Set<String> { [] }
    public var supportsSafetyNumbers: Bool { false }

    public init() {}

    public func chats(in folder: ChatFolder) -> [Chat] { [] }
    public func folderChipTitle(_ folder: ChatFolder) -> String { folder.name }
    public func chat(id: String) -> Chat? { nil }
    public func contact(id: String) -> Contact? { nil }
    public func messages(in chatID: String, before messageID: String?, limit: Int) -> [Message] { [] }
    public func searchMessages(_ query: String, limit: Int) -> [MessageHit] { [] }
    public func send(_ content: MessageContent, in chatID: String, replyTo: String?) {}
    public func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String) {}
    public func react(_ emoji: String, to messageID: String, in chatID: String) {}
    public func edit(messageID: String, in chatID: String, text: String) {}
    public func delete(messageID: String, in chatID: String) {}
    public func download(messageID: String, in chatID: String) {}
    public func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date) {}
    public func safetyNumber(for chatID: String) -> SafetyNumber? { nil }
    public func setVerified(_ verified: Bool, chatID: String) {}
    public func receive(_ content: MessageContent, from contact: Contact) {}
    public func accept(requestID: String) {}
    public func block(contactID: String) {}
    public func report(chatID: String) {}
    public func markRead(chatID: String) {}
    public func incomingCall(from contact: Contact, video: Bool) -> IncomingCallDecision { .silentMissed }
    public func addContact(username: String) async -> Contact? { nil }
    public func ensureChat(with contactID: String) -> String { contactID }
    public func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder {
        ChatFolder(id: UUID().uuidString, name: name, kind: .custom)
    }
    public func renameFolder(id: String, to name: String) {}
    public func deleteFolder(id: String) {}
    public func moveFolder(id: String, toCustomIndex index: Int) {}
}

/// A live account's call engine until the real one exists: it never starts, rings or simulates a call.
public final class InertCallEngine: CallEngine {
    public var onChange: ((CallState?) -> Void)?
    public var state: CallState? { nil }
    public init() {}
    public func start(with contact: Contact, video: Bool) {}
    public func setPreference(_ preference: ModePreference) {}
    public func setMuted(_ on: Bool) {}
    public func setSpeaker(_ on: Bool) {}
    public func setVideo(_ on: Bool) {}
    public func end() {}
    public func answer(video: Bool) {}
    public func decline() {}
    public func setNetwork(_ type: NetworkType) {}
    public func reset() {}
}

/// The chat store and call engine the app starts with. `-sampleData`: the sample store and the simulated engine.
/// A live account: an empty store and an inert engine until the live ones replace them; nothing sample is built.
@MainActor
public struct AppStores {
    public let chatStore: any ChatStore
    /// `nil` for a live account.
    public let sampleCalls: SimulatedCallEngine?
    /// The engine used while there is no live engine.
    public let idleCalls: any CallEngine

    public init(sampleData: Bool, server: ServerPolicy, settings: CallSettings, network: NetworkType) {
        chatStore = Self.signedOutStore(sampleData: sampleData)
        if sampleData {
            let simulated = SimulatedCallEngine(server: server, settings: settings, network: network)
            sampleCalls = simulated
            idleCalls = simulated
        } else {
            sampleCalls = nil
            idleCalls = InertCallEngine()
        }
    }

    /// The store after sign-out (or when the live store could not be built).
    public static func signedOutStore(sampleData: Bool) -> any ChatStore {
        sampleData ? SampleChatStore() : EmptyChatStore()
    }
}

/// How the app runs, decided once at launch.
public enum AppLaunchMode: Equatable, Sendable {
    /// `-sampleData`: sample accounts and chats, no server.
    case sample
    /// A live account on the server.
    case live
    /// This build lacks a required shared group (`missing` Info.plist keys): the app shows a blocking error. It never
    /// signs in and never shows sample chats.
    case brokenSetup(missing: [String])

    public static func resolve(sampleData: Bool, setupProblems: [String]) -> AppLaunchMode {
        if sampleData { return .sample }
        return setupProblems.isEmpty ? .live : .brokenSetup(missing: setupProblems)
    }

    public var showsSampleData: Bool { self == .sample }
    public var isLive: Bool { self == .live }
}
