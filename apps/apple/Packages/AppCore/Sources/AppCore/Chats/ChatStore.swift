import Foundation

public enum IncomingCallDecision: Equatable, Sendable {
    /// Show the call screen.
    case ring
    /// Don't ring; record a missed call in the message request.
    case silentMissed
}

/// Reads one chat's photo and file messages, newest first: up to `limit` older than `before` (the newest when nil).
/// Sendable and safe to call off the main actor, so a gallery can load without blocking the screen.
public typealias AttachmentPager = @Sendable (_ before: String?, _ limit: Int) -> [Message]

/// Chats, contacts, and message requests. `SampleChatStore` now; the real MLS-backed store later.
@MainActor
public protocol ChatStore: PeopleStore {
    var me: Contact { get }
    /// The own name as stored on this device, or "" when none is stored. `me.displayName` shows the placeholder
    /// "You" in that case; the placeholder is display only and is never offered, sent or stored.
    var myStoredName: String { get }
    /// Shows the name at once and stores it in the own contact row.
    func setMyDisplayName(_ name: String)
    var contacts: [Contact] { get }
    var folders: [ChatFolder] { get }
    func chats(in folder: ChatFolder) -> [Chat]
    func folderChipTitle(_ folder: ChatFolder) -> String
    var messageRequests: [Chat] { get }
    var messageRequestsRowTitle: String? { get }
    /// Account IDs this device has blocked (Settings > Privacy).
    var blockedIDs: Set<String> { get }
    /// Chats this device archived, in list order. They are left out of `chats(in:)`.
    var archivedChats: [Chat] { get }
    /// Chats this device muted: their notifications are silent and their unread count is neutral.
    var mutedChatIDs: Set<String> { get }
    func chat(id: String) -> Chat?
    func contact(id: String) -> Contact?
    /// Called after any change to the store's data.
    var onChange: (@MainActor () -> Void)? { get set }
    /// Up to `limit` messages before `messageID` (the newest if nil), oldest first.
    func messages(in chatID: String, before messageID: String?, limit: Int) -> [Message]
    func searchMessages(_ query: String, limit: Int) -> [MessageHit]
    /// Hits inside one chat.
    func searchMessages(_ query: String, in chatID: String, limit: Int) -> [MessageHit]
    func send(_ content: MessageContent, in chatID: String, replyTo: String?)
    func sendAttachment(_ fileURL: URL, kind: MediaKind, in chatID: String)
    /// Photos and videos from the preview screen, in order, with the caption sent as a text message after the first.
    func sendMedia(_ items: [OutgoingMedia], caption: String, in chatID: String)
    /// Stops an upload that has not finished and removes its message, its queue entry and its files. After the
    /// message is sent it does nothing.
    func cancelUpload(messageID: String, in chatID: String)
    /// A reader for the chat's photo and file messages (see `AttachmentPager`).
    func attachmentPager(for chatID: String) -> AttachmentPager
    /// A recorded voice note of `duration` seconds.
    func sendVoice(_ fileURL: URL, duration: Double, in chatID: String)
    /// Sends a message that gave up (`DeliveryStatus.failed`) again.
    func retrySend(messageID: String)
    func react(_ emoji: String, to messageID: String, in chatID: String)
    func edit(messageID: String, in chatID: String, text: String)
    func delete(messageID: String, in chatID: String)
    func download(messageID: String, in chatID: String)
    func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date)
    /// False while the engine cannot give real member keys: the Verify screen and the Verified seal stay hidden.
    var supportsSafetyNumbers: Bool { get }
    /// `nil` until the chat has a conversation.
    func safetyNumber(for chatID: String) -> SafetyNumber?
    /// Local only.
    func setVerified(_ verified: Bool, chatID: String)
    /// The members of one of the chat's groups could not be read: no safety number, and chat info says so.
    func membersUnreadable(for chatID: String) -> Bool
    func receive(_ content: MessageContent, from contact: Contact)
    func accept(requestID: String)
    func block(contactID: String)
    /// Lifts a block (Settings > Privacy > Blocked). Local at once; the server is told in the background.
    func unblock(contactID: String)
    func report(chatID: String)
    func markRead(chatID: String)
    /// Pins or unpins on this device. False when 5 chats are already pinned or the chat is archived.
    @discardableResult func setPinned(_ chatID: String, _ pinned: Bool) -> Bool
    func setMuted(_ chatID: String, _ muted: Bool)
    /// Archiving also unpins; unarchiving does not pin again.
    func setArchived(_ chatID: String, _ archived: Bool)
    /// The unsent text of a chat, kept on this device. Blank text removes it. Called on every keystroke: it shows at once
    /// and does not call `onChange`; the database write may come later (see `flushDrafts`).
    func setDraft(_ chatID: String, _ text: String)
    /// The unsent text to show in the composer, also for a chat that has no row yet. "" when there is none.
    func draft(for chatID: String) -> String
    /// Writes every draft not yet saved and calls `onChange` when it wrote something. Called when a chat is left and when
    /// the app stops being active.
    func flushDrafts()
    func incomingCall(from contact: Contact, video: Bool) -> IncomingCallDecision
    func addContact(username: String) async -> Contact?
    /// The chat with this contact, created empty if there is none yet.
    func ensureChat(with contactID: String) -> String
    /// Custom folders only; All and Unread are fixed.
    func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder
    func renameFolder(id: String, to name: String)
    func deleteFolder(id: String)
    func moveFolder(id: String, toCustomIndex index: Int)
}

/// A photo or video picked for sending: a file the engine may copy.
public struct OutgoingMedia: Equatable, Sendable {
    public var url: URL
    public var kind: MediaKind
    /// The file is the app's own temporary copy: it is deleted as soon as the store has copied it for sending.
    public var deleteAfterStaging: Bool

    public init(url: URL, kind: MediaKind, deleteAfterStaging: Bool = false) {
        self.url = url
        self.kind = kind
        self.deleteAfterStaging = deleteAfterStaging
    }
}

/// The name and photo a contact sent in a `profile` message, with its version.
public struct StoredProfile: Equatable, Sendable {
    public var accountID: String
    public var name: String
    /// A JPEG of at most 24,576 bytes.
    public var photo: Data?
    public var ver: Int

    public init(accountID: String, name: String, photo: Data?, ver: Int) {
        self.accountID = accountID
        self.name = name
        self.photo = photo
        self.ver = ver
    }
}

/// Disappearing messages and contact profiles. A separate protocol so stores without a database keep working
/// through the defaults below.
@MainActor
public protocol PeopleStore: AnyObject {
    /// Seconds a new message in the chat lives: 0 (off), 86,400, 604,800 or 7,776,000.
    func timerSeconds(_ chatID: String) -> Int
    /// Sets the chat's timer on this device, writes the system line and tells the other side (`timer` message).
    /// A number that is not a `TimerChoice` is ignored.
    func setTimer(_ chatID: String, seconds: Int)
    /// Removes every message whose time has come (row, attachment files, search entry), and returns their IDs so the
    /// caller can remove delivered notifications.
    @discardableResult func sweepExpired(now: Date) -> [String]
    /// The earliest time a message will expire, for the sweep timer; `nil` when none will.
    func nextExpiry() -> Date?
    func profile(for accountID: String) -> StoredProfile?
    /// Keeps the profile when `ver` is strictly higher than the stored one; `false` (nothing changes) otherwise.
    @discardableResult func saveProfile(accountID: String, name: String, photo: Data?, ver: Int) -> Bool
    /// The own profile photo (a JPEG of at most 24,576 bytes), or `nil`.
    var myPhoto: Data? { get }
    /// Keeps the photo (`nil` removes it) and sends the new profile to every one-to-one chat. `false` and nothing
    /// changes when the data is not a JPEG within the limit.
    @discardableResult func setMyPhoto(_ photo: Data?) -> Bool
    /// The photo to draw for this person (the own account included), or `nil` for the coloured letter.
    func profilePhoto(for accountID: String) -> Data?
}

extension PeopleStore {
    public func timerSeconds(_ chatID: String) -> Int { 0 }
    public func setTimer(_ chatID: String, seconds: Int) {}
    @discardableResult public func sweepExpired(now: Date) -> [String] { [] }
    public func nextExpiry() -> Date? { nil }
    public func profile(for accountID: String) -> StoredProfile? { nil }
    @discardableResult public func saveProfile(accountID: String, name: String, photo: Data?, ver: Int) -> Bool { false }
    public var myPhoto: Data? { nil }
    @discardableResult public func setMyPhoto(_ photo: Data?) -> Bool { false }
    public func profilePhoto(for accountID: String) -> Data? { nil }
}

extension ChatStore {
    /// For stores without a database: a snapshot of the chat's messages taken now.
    public func attachmentPager(for chatID: String) -> AttachmentPager {
        let newestFirst = Array(messages(in: chatID, before: nil, limit: 100_000).filter { ChatMedia.kind(of: $0) != nil }.reversed())
        return { before, limit in
            var start = 0
            if let before {
                guard let index = newestFirst.firstIndex(where: { $0.id == before }) else { return [] }
                start = index + 1
            }
            return Array(newestFirst.dropFirst(start).prefix(limit))
        }
    }

    /// One after the other, the caption after the first item.
    public func sendMedia(_ items: [OutgoingMedia], caption: String, in chatID: String) {
        let text = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        for (index, item) in items.enumerated() {
            sendAttachment(item.url, kind: item.kind, in: chatID)
            if item.deleteAfterStaging { try? FileManager.default.removeItem(at: item.url) }
            if index == 0, !text.isEmpty { send(.text(text), in: chatID, replyTo: nil) }
        }
    }

    public func cancelUpload(messageID: String, in chatID: String) {}

    public func draft(for chatID: String) -> String { chat(id: chatID)?.draft ?? "" }

    public func flushDrafts() {}

    /// Searches every chat for the newest 500 hits and keeps this chat's. A store with a chat-scoped query should override it.
    public func searchMessages(_ query: String, in chatID: String, limit: Int) -> [MessageHit] {
        Array(searchMessages(query, limit: 500).filter { $0.chatID == chatID }.prefix(limit))
    }

    public func sendVoice(_ fileURL: URL, duration: Double, in chatID: String) {
        sendAttachment(fileURL, kind: .voice, in: chatID)
    }

    /// Stores that never fail to send have nothing to retry.
    public func retrySend(messageID: String) {}

    /// Stores without an MLS engine can always read their members.
    public func membersUnreadable(for chatID: String) -> Bool { false }

    public func send(_ content: MessageContent, in chatID: String) {
        send(content, in: chatID, replyTo: nil)
    }
}
