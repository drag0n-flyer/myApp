import AppCore
import Foundation
import UserNotifications
#if canImport(UIKit)
import UIKit
#endif

/// Notification categories and what the Reply and Mark as read buttons do. Every decision is in
/// `AppCore.NotificationPlan`; this type only talks to the system and the store.
///
/// A reply goes to the chat named by the notification's thread identifier (and `userInfo["chatID"]` when present;
/// they must agree), is claimed once, and is checked against the store. It is either queued in the outbox (the store's
/// normal send path) or appended to that chat's draft with a "Not sent" notice. Everything is bounded by
/// `ActionBudget` (20 seconds, one background task, ended on every path).
@MainActor
final class NotificationActions: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationActions()

    nonisolated static let replyActionID = "REPLY"
    nonisolated static let markReadActionID = "MARK_READ"
    nonisolated static let notSentPrefix = NotificationPlan.notSentPrefix

    private(set) weak var model: AppModel?
    private var claims = ReplyClaims()
    private var syncTask: Task<Void, Never>?
    private var syncAgain = false
    private var lastBadge: Int?
    private var settingsPublished = false
    private var lastUnread: [String: Int]?
    private var lastChatIDs: Set<String>?
    private var lastBlocked: Set<String> = []
    private var lastSettings: NotificationSettings?

    private override init() { super.init() }

    /// `message` offers Reply and Mark as read; `message.locked` offers Mark as read only.
    static var categories: Set<UNNotificationCategory> {
        let reply = UNTextInputNotificationAction(
            identifier: replyActionID, title: String(localized: "Reply"), options: [],
            textInputButtonTitle: String(localized: "Reply"), textInputPlaceholder: String(localized: "Message"))
        let markRead = UNNotificationAction(
            identifier: markReadActionID, title: String(localized: "Mark as read"), options: [])
        return [
            UNNotificationCategory(
                identifier: NotificationPlan.messageCategory, actions: [reply, markRead], intentIdentifiers: [], options: []),
            UNNotificationCategory(
                identifier: NotificationPlan.lockedCategory, actions: [markRead], intentIdentifiers: [], options: []),
        ]
    }

    /// iPhone: registers the categories and becomes the notification delegate. Called from the app delegate's launch,
    /// so a response that cold-starts the app is delivered. On the Mac `MacRinger` is the delegate and registers these
    /// categories with its own (`setNotificationCategories` replaces the whole set).
    func installOnLaunch() {
        #if os(iOS)
        let center = UNUserNotificationCenter.current()
        center.setNotificationCategories(Self.categories)
        center.delegate = self
        #endif
    }

    func attach(_ model: AppModel) { self.model = model }

    // MARK: UNUserNotificationCenterDelegate (iPhone; the Mac's `MacRinger` forwards responses to `handle`)

    /// Unchanged from before: nothing is shown while the app is in the foreground.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        []
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await handle(response)
    }

    // MARK: Responses

    private enum Action: Sendable {
        case reply(requestID: String, chatID: String, text: String)
        case markRead(chatID: String)
        case open(chatID: String)
    }

    nonisolated func handle(_ response: UNNotificationResponse) async {
        let request = response.notification.request
        let content = request.content
        let chatID = NotificationRoute.chatID(
            userInfoChatID: content.userInfo["chatID"] as? String, threadID: content.threadIdentifier)
        guard let chatID else { return }
        switch response.actionIdentifier {
        case Self.replyActionID:
            guard let text = (response as? UNTextInputNotificationResponse)?.userText else { return }
            await perform(.reply(requestID: request.identifier, chatID: chatID, text: text))
        case Self.markReadActionID:
            await perform(.markRead(chatID: chatID))
        case UNNotificationDefaultActionIdentifier:
            await perform(.open(chatID: chatID))   // a tap on the body opens the chat (after unlock, when locked)
        default:
            break
        }
    }

    private func perform(_ action: Action) async {
        let budget = ActionBudget()
        defer { budget.end() }
        switch action {
        case .reply(let requestID, let chatID, let text): await reply(requestID, chatID, text, budget)
        case .markRead(let chatID): await markRead(chatID, budget)
        case .open(let chatID): await open(chatID, budget)
        }
    }

    private func reply(_ requestID: String, _ chatID: String, _ raw: String, _ budget: ActionBudget) async {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, claims.claim(requestID: requestID, text: text) else { return }
        guard let model = await readyModel(budget) else {
            // No signed-in store to send through or keep a draft in: say so, naming nobody.
            await postNotSent(chatID: chatID, contactName: nil)
            return
        }
        let store = model.chatStore
        let locked = NotificationHooks.appLockOn()
        let chat = store.chat(id: chatID)
        let decision = NotificationPlan.replyDecision(
            chatKnown: chat != nil, isRequest: chat?.isRequest ?? false,
            isBlocked: chat.flatMap(\.contactID).map { store.blockedIDs.contains($0) } ?? false,
            textLength: text.count, appLockOn: locked)
        var queued = false
        var queuedID: String?
        if decision == .send, !budget.expired {
            // `send` returns nothing: the reply counts as queued only when the newest message is now this one, ours.
            let before = store.messages(in: chatID, before: nil, limit: 1).last?.id
            model.send(.text(text), in: chatID)
            if let after = store.messages(in: chatID, before: nil, limit: 1).last {
                queued = after.id != before && after.senderID == store.me.id && after.content == .text(text)
                if queued { queuedID = after.id }
            }
        }
        if let queuedID { await waitForFlush(queuedID, chatID: chatID, store: store, budget: budget) }
        if ReplyDraft.outcome(decision: decision, queued: queued) == .keepDraft {
            NotificationHooks.saveReplyDraft(chatID: chatID, text: text)
            await postNotSent(chatID: chatID, contactName: locked ? nil : (chat?.title ?? model.contact(id: chat?.contactID ?? chatID)?.displayName))
        }
    }

    /// The reply is already durable in the outbox. Keep the app alive a little longer, polling every 200 ms, so the
    /// send can leave before iOS suspends it. Stops when the message is no longer "sending", or at the bound.
    private func waitForFlush(_ messageID: String, chatID: String, store: any ChatStore, budget: ActionBudget) async {
        let wait = NotificationPlan.flushWait(remaining: budget.remaining)
        let end = Date().addingTimeInterval(Double(wait.components.seconds) + Double(wait.components.attoseconds) / 1e18)
        while Date() < end {
            let message = store.messages(in: chatID, before: nil, limit: 20).first { $0.id == messageID }
            if message == nil || message?.status != .sending { return }
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    private func markRead(_ chatID: String, _ budget: ActionBudget) async {
        guard let model = await readyModel(budget), model.chat(id: chatID) != nil else { return }
        model.markRead(chatID: chatID)   // also removes the chat's delivered notifications
    }

    private func open(_ chatID: String, _ budget: ActionBudget) async {
        guard let model = await readyModel(budget), model.chat(id: chatID) != nil else { return }
        model.openChatFromOutside(chatID)
    }

    /// The model once the signed-in store is built (a notification action can cold-start the app), or nil at the deadline.
    private func readyModel(_ budget: ActionBudget) async -> AppModel? {
        while Date() < budget.waitDeadline, !budget.expired {
            if let model, model.notificationStoreReady { return model }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return nil
    }

    // MARK: Posting

    /// "Not sent" for one chat. `contactName` nil (locked, or not known) shows the app name instead.
    private func postNotSent(chatID: String, contactName: String?) async {
        let content = UNMutableNotificationContent()
        content.title = contactName ?? AppBrand.name
        content.body = String(localized: "Not sent")
        content.threadIdentifier = chatID
        content.userInfo = ["chatID": chatID]
        content.sound = .default
        let request = UNNotificationRequest(identifier: Self.notSentPrefix + chatID, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    // MARK: Delivered notifications

    /// Removes every delivered notification of one chat (read in the app, marked read, blocked).
    func removeDelivered(chatID: String) {
        Task { await Self.removeDeliveredNow(chatID: chatID) }
    }

    nonisolated private static func removeDeliveredNow(chatID: String) async {
        let center = UNUserNotificationCenter.current()
        let ids = await center.deliveredNotifications()
            .filter { $0.request.content.threadIdentifier == chatID }
            .map(\.request.identifier)
        if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
    }

    /// Replaces every delivered message notification with its locked form (no name, no text). A6 calls this when
    /// app lock is switched on. The settings file is written first, so the extension hides content from then on too.
    func hideDeliveredContent() async {
        publishSettings(force: true)
        await Self.hideDeliveredNow()
    }

    nonisolated private static func hideDeliveredNow() async {
        let center = UNUserNotificationCenter.current()
        for note in await center.deliveredNotifications() {
            let old = note.request.content
            let rewrite = NotificationPlan.lockedRewrite(
                identifier: note.request.identifier, category: old.categoryIdentifier,
                threadID: old.threadIdentifier, hasCallID: old.userInfo["callID"] != nil)
            let content = UNMutableNotificationContent()
            switch rewrite {
            case .keep:
                continue
            case .hideMessage(let chatID):
                let plan = NotificationPlan.lockedForm(chatID: chatID)
                content.title = AppBrand.name
                content.body = String(localized: String.LocalizationValue(plan.body))
                content.threadIdentifier = plan.threadIdentifier
                content.categoryIdentifier = plan.category
                content.userInfo = ["chatID": chatID]
            case .hideName:
                // "Not sent" or a missed call: the body names nobody; only the title does.
                content.title = AppBrand.name
                content.body = old.body
                content.threadIdentifier = old.threadIdentifier
                content.categoryIdentifier = old.categoryIdentifier
                content.userInfo = old.userInfo
            }
            content.interruptionLevel = .passive   // replaced quietly, never alerts again
            // The same identifier replaces the delivered notification.
            try? await center.add(UNNotificationRequest(identifier: note.request.identifier, content: content, trigger: nil))
        }
    }

    // MARK: Following the store

    /// Called after every store change. One pass per burst: settings file, badge, and notifications of chats that were
    /// read, left the list or were blocked.
    func storeChanged() {
        if syncTask != nil { syncAgain = true; return }   // one sync at a time; it runs once more when it finishes
        syncTask = Task { [weak self] in
            repeat {
                self?.syncAgain = false
                await Task.yield()
                await self?.sync()
            } while self?.syncAgain == true
            self?.syncTask = nil
        }
    }

    private func sync() async {
        guard let model, !model.isSampleData, !model.isUITesting, model.notificationStoreReady else { return }
        let store = model.chatStore
        let chats = store.chats(in: .all)
        let unread = Dictionary(chats.map { ($0.id, $0.unreadCount) }, uniquingKeysWith: max)
        let ids = Set(unread.keys)
        let blocked = store.blockedIDs
        var gone = Set<String>()
        if let lastUnread, let lastChatIDs {
            gone = NotificationPlan.readChats(before: lastUnread, after: unread)
                .union(NotificationPlan.leftChats(before: lastChatIDs, after: ids, blockedBefore: lastBlocked, blockedAfter: blocked))
        }
        lastUnread = unread
        lastChatIDs = ids
        lastBlocked = blocked
        for chatID in gone { await Self.removeDeliveredNow(chatID: chatID) }
        publishSettings(force: !settingsPublished)   // the first time as soon as the store is ready
        // Archived chats are already left out of `chats(in: .all)`; nothing else to pass.
        let count = NotificationPlan.badgeCount(unread: unread, muted: NotificationHooks.mutedChatIDs(), archived: [])
        if count != lastBadge {
            lastBadge = count
            try? await UNUserNotificationCenter.current().setBadgeCount(count)
        }
    }

    /// Writes the two settings the notification extension reads. Only when they changed.
    private func publishSettings(force: Bool) {
        guard let model, !model.isSampleData, !model.isUITesting, let container = LiveMessaging.container else { return }
        let settings = NotificationSettings(
            mutedChatIDs: NotificationHooks.mutedChatIDs(), appLockOn: NotificationHooks.appLockOn(),
            requestChatIDs: Set(model.chatStore.messageRequests.map(\.id)))
        guard force || settings != lastSettings else { return }
        let url = container.appendingPathComponent(NotificationSettings.fileName)
        #if os(iOS)
        let options: Data.WritingOptions = [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        #else
        let options: Data.WritingOptions = [.atomic]
        #endif
        if (try? settings.encoded().write(to: url, options: options)) != nil {
            lastSettings = settings
            settingsPublished = true
        }
    }

    /// Sign-out: nothing of the account stays in Notification Center or on the icon, and the extension reads
    /// "locked" until the next sign-in writes the file again.
    func signedOut() {
        lastUnread = nil
        lastChatIDs = nil
        lastBlocked = []
        lastSettings = nil
        settingsPublished = false
        lastBadge = 0
        if let container = LiveMessaging.container {
            try? FileManager.default.removeItem(at: container.appendingPathComponent(NotificationSettings.fileName))
        }
        guard !(model?.isUITesting ?? true) else { return }
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        Task { try? await center.setBadgeCount(0) }
    }
}

/// The 20 seconds a notification action may run. On the iPhone a background task keeps the app alive that long and is
/// ended on every path (by `end`, or by the system's expiry handler, whichever comes first).
@MainActor
private final class ActionBudget {
    static let seconds: TimeInterval = 20
    /// Waiting for the store to be built stops earlier, leaving time to keep a draft.
    static let waitSeconds: TimeInterval = 14

    let waitDeadline = Date().addingTimeInterval(waitSeconds)
    private let deadline = Date().addingTimeInterval(seconds)
    private var flag = false
    private var timer: Task<Void, Never>?
    #if os(iOS)
    private var taskID = UIBackgroundTaskIdentifier.invalid
    #endif

    var expired: Bool { flag || Date() >= deadline }
    /// What is left of the 20 seconds.
    var remaining: Duration { flag ? .zero : .seconds(max(0, deadline.timeIntervalSinceNow)) }

    init() {
        #if os(iOS)
        taskID = UIApplication.shared.beginBackgroundTask(withName: "notification-action") { [weak self] in
            MainActor.assumeIsolated { self?.expire() }
        }
        #endif
        timer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.seconds))
            self?.expire()
        }
    }

    private func expire() {
        flag = true
        endBackgroundTask()
    }

    func end() {
        timer?.cancel()
        endBackgroundTask()
    }

    private func endBackgroundTask() {
        #if os(iOS)
        if taskID != .invalid {
            UIApplication.shared.endBackgroundTask(taskID)
            taskID = .invalid
        }
        #endif
    }
}
