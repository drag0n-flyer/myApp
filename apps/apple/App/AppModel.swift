import AppCore
import CallCore
import Foundation
import Messaging
import Observation
import ServerKit
import SwiftUI
import UserNotifications
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

enum AppTab: Hashable {
    case contacts, calls, chats, settings, search
}

/// Root state for the app. Wraps the sample services; real services replace them later
/// behind the same AppCore protocols.
@MainActor
@Observable
final class AppModel {
    /// `SampleChatStore` with `-sampleData`. A live account: `EmptyChatStore` until `LiveChatStore` is built
    /// (`installLiveStore`); a live account never sees sample chats (`AppStores`).
    private(set) var chatStore: any ChatStore
    /// The simulated engine, `-sampleData` only.
    let sampleCalls: SimulatedCallEngine?
    /// `calls` while there is no live engine: the simulated engine with sample data, an inert one for a live account.
    private let idleCalls: any CallEngine
    /// The real engine, once signed in (it needs the live connection and the live store). `nil` in `-sampleData` mode.
    private(set) var liveCalls: LiveCallEngine?
    var calls: any CallEngine {
        if let liveCalls { return liveCalls }
        return idleCalls
    }
    /// The live store could not be built after sign-in: an error is shown, with a retry. No sample data stands in.
    var liveStoreUnavailable = false
    /// Open while the live store and call engine are in place; a VoIP push that woke the app waits on it.
    private let callReady = ReadinessGate()
    let account: any AccountService
    /// `nil` in `-sampleData` mode.
    private let live: LiveAccountService?
    let config = AppConfig.current
    let isSampleData: Bool
    /// Shown after the server ends this device's session.
    var deviceRemovedAlert = false
    /// This build lacks the shared app group or keychain group (`AppLaunchMode.brokenSetup`): live sign-in is refused
    /// (the session would be stranded where the notification extension cannot read it) and a blocking error is shown.
    /// Never sample data.
    let hasBrokenSetup: Bool
    private var connection: LiveConnection?
    private let sessions: SessionStore?
    private var messaging: LiveMessaging?
    /// The wipe of the last signed-out account; the next sign-in waits for it, so it never opens half-deleted data.
    private var wipeTask: Task<Void, Never>?
    /// A wipe failed every try (`DeviceSync.wipeAttempts`): no live store, connection or sign-in work runs until
    /// the app starts again, which finishes the wipe first.
    private var wipeFailed = false
    private var liveTasks: [Task<Void, Never>] = []
    /// Typing and presence (see `AppModel+People`).
    @ObservationIgnored var people: PeopleSignals?
    @ObservationIgnored var peopleTasks: [Task<Void, Never>] = []
    @ObservationIgnored var peopleActive = true
    /// Gives the account service, built before this model, this device's link snapshot to send.
    private let linkSnapshots: LinkSnapshotBridge
    private var apnsToken: Data?
    private var registrar: PushRegistrar?
    /// Seals call signals through the live chat store once it is installed; fails closed before that.
    private let callSealer = SwitchableCallSealer()
    private var pathMonitor: PathMonitor?
    private var isBackground = false
    /// Removes expired messages (and their notifications): at start, on foreground, and when the next one is due.
    @ObservationIgnored private var expirySweeper: ExpirySweeper?
    private var connectionState: ConnectionState = .idle
    /// False when the device has no network path (`NetworkReachability`); true until the first report.
    private var hasNetwork = true
    private var reachability: NetworkReachability?
    /// The rules of a deliberate sign-out (`SignOutGate`): one at a time, and the server's end-of-session signals that
    /// follow it (a 4003 close, a late 401) are ignored until the next sign-in.
    private var signOutGate = SignOutGate()
    var isSigningOut: Bool { signOutGate.inFlight }
    /// Save name, sign out and delete account run one at a time (`AccountActionGate`).
    private var accountGate = AccountActionGate()
    var accountAction: AccountAction? { accountGate.running }
    /// Bumped on every sign-out and sign-in: a name read or saved in an older session is not written.
    private var sessionGeneration = 0
    /// "Find me by email" is being saved; a second change is ignored and a load keeps the person's value.
    private(set) var savingFindByEmail = false
    private var findByEmailChanged = false
    /// Newest stats of the running call, for the call info sheet.
    private(set) var callStats: MediaStats?
    #if os(iOS)
    @ObservationIgnored private var callKit: CallKitController?
    @ObservationIgnored private var voipCenter: VoIPPushCenter?
    private var voipToken: Data?
    #endif

    var server: ServerPolicy {
        didSet { syncCalls() }
    }
    /// Where the three privacy switches are kept (the app lock's defaults); `nil` in sample mode, which keeps nothing.
    private let privacyStore: PrivacySwitchesStore?
    var settings: UserSettings {
        didSet {
            savePrivacySwitches(oldValue)
            syncCalls()
            if settings.linkPreviews != oldValue.linkPreviews, !isSampleData { LinkPreviewSetting.save(settings.linkPreviews) }
            messaging?.autoDownload.value = settings.autoDownload
            if settings.readReceipts != oldValue.readReceipts, let messaging {
                messaging.readReceipts.value = settings.readReceipts
                // Others' read ticks show or hide with the switch.
                messaging.store.refresh()
            }
        }
    }
    var network: NetworkType {
        didSet {
            sampleCalls?.setNetwork(network)   // the live engine is told by the path monitor, with its verdict
            messaging?.network.value = network
        }
    }

    private func syncCalls() {
        sampleCalls?.server = server
        sampleCalls?.settings = settings.call
        liveCalls?.update(server: server, settings: settings)
    }
    /// What the chat list strip and Settings > Server show. Sample mode is always connected.
    var connectionStatus: ConnectionStatus {
        if isSampleData { return .connected }
        // No retry will follow in these states: say so instead of "Connecting…" forever.
        if connectionState == .replaced || liveStoreUnavailable || wipeFailed { return .offline }
        return ConnectionStatus.from(socketConnected: connectionState == .connected, hasNetwork: hasNetwork)
    }
    var onboarding: OnboardingFlow
    var selectedTab: AppTab = .chats
    var selectedFolder: ChatFolder = .all
    /// Text in the iOS 26 search tab.
    var searchText = ""
    /// Filter typed into the chat list's own search field (iOS 17–25, Mac).
    var chatListQuery = ""
    /// Chat opened from outside the chat list (Mac sidebar, Contacts, Calls).
    var openChatID: String?
    /// The chat on screen in the main window (Mac, iPad); the menu commands act on it. The roots keep it current and
    /// clear it when they leave the screen (the lock cover removes them).
    var onScreenChatID: String?
    /// Menu commands ask the root for something by counting up; the root watches the number (see `AppModel+Commands`).
    var newChatRequests = 0
    var listSearchRequests = 0
    var chatInfoRequests = 0
    var closeChatRequests = 0
    private(set) var activeCall: CallState?
    var pendingInvite: InviteLink?
    /// Mac only: a contact who is ringing (macOS has no CallKit).
    var incomingCaller: Contact?
    #if os(macOS)
    @ObservationIgnored private(set) var macRinger: MacRinger?
    #endif
    let isUITesting: Bool
    /// The app lock (Face ID, Touch ID or the device passcode in front of the UI). Created in `init`; the root shows
    /// only the cover while `lock.coversContent`.
    let lock: AppLock

    /// The attachment size limit the server advertised (nil until known).
    var serverAttachmentLimit: Int64?

    /// Bumped after every store change so views that read store data refresh.
    private(set) var revision = 0 {
        didSet { NotificationActions.shared.storeChanged() }
    }

    /// The signed-in store is built (or this is sample data): a notification action can use it.
    var notificationStoreReady: Bool { isSampleData || messaging != nil }

    init(launch: LaunchOptions = .current) {
        let config = AppConfig.current
        #if os(macOS)
        let platform = DevicePlatform.mac
        let deviceName = Host.current().localizedName ?? "Mac"
        #else
        let platform = DevicePlatform.iPhone
        let deviceName = UIDevice.current.name
        #endif
        let bridge = LinkSnapshotBridge()
        let account: any AccountService
        var live: LiveAccountService?
        var sessionStore: SessionStore?
        // Fails closed like `makeMLSEngine`: no live account without both shared groups.
        let setupProblems = launch.sampleData ? [] : MessagingLocations.liveSetupProblems(in: Bundle.main.infoDictionary ?? [:])
        let mode = AppLaunchMode.resolve(sampleData: launch.sampleData, setupProblems: setupProblems)
        let sampleData = mode.showsSampleData
        if !mode.isLive {
            // Sample mode; or a broken setup, whose blocking error screen never lets anyone reach this service.
            account = SampleAccountService(platform: platform)
        } else {
            let sessions = SessionStore(storage: KeychainSessionStorage(accessGroup: config.keychainAccessGroup))
            let service = LiveAccountService(
                defaultHost: config.defaultServerHost, passkeyDomains: config.passkeyDomains, platform: platform,
                deviceName: deviceName, sessions: sessions,
                makeClient: { ServerClient(host: $0, session: sessions) },
                passkeys: PasskeyAuthenticator(),
                approvalReason: String(localized: "Confirm to approve this new device."),
                linkSnapshot: { await bridge.export() })
            account = service
            live = service
            sessionStore = sessions
        }
        let server = account.defaultServer
        var settings = UserSettings.initial(from: server)
        let lock = AppModel.makeLock(sampleData: sampleData, launch: launch)
        settings.appLock = lock.appLockOn
        settings.lockAfterSeconds = lock.lockAfterSeconds
        settings.linkPreviews = !sampleData && LinkPreviewSetting.load()
        // The privacy switches are read before anything is sent or shown, so an Off survives a relaunch.
        let privacyStore = sampleData ? nil : PrivacySwitchesStore(defaults: AppModel.lockDefaults())
        if let loaded = privacyStore?.load() {
            settings.typingIndicator = loaded.typingIndicator
            settings.onlineAndLastSeen = loaded.onlineAndLastSeen
            settings.readReceipts = loaded.readReceipts
        }
        self.privacyStore = privacyStore
        self.account = account
        self.live = live
        self.linkSnapshots = bridge
        self.sessions = sessionStore
        self.isSampleData = sampleData
        self.hasBrokenSetup = !setupProblems.isEmpty
        self.server = server
        self.settings = settings
        self.network = launch.network
        self.isUITesting = launch.isUITesting
        // A recording the app was killed in the middle of is not kept.
        VoiceSession.removeOrphans()
        self.lock = lock
        let stores = AppStores(sampleData: sampleData, server: server, settings: settings.call, network: launch.network, memberGroup: launch.memberGroup)
        let chatStore = stores.chatStore
        self.chatStore = chatStore
        self.sampleCalls = stores.sampleCalls
        self.idleCalls = stores.idleCalls
        #if os(macOS)
        var flow = OnboardingFlow(platform: .mac)
        #else
        var flow = OnboardingFlow(platform: .iPhone)
        #endif
        if launch.skipOnboarding {
            flow.alreadyHaveAccount()
            flow.linked()
        }
        if let live, live.isSignedIn {
            flow.alreadyHaveAccount()
            flow.linked()
        }
        self.onboarding = flow
        live?.onSignedOut = { [weak self] reason in self?.serverEndedSession(reason) }
        linkSnapshots.model = self
        live?.importLinkSnapshot = { [weak self] data in
            guard let self else { throw CancellationError() }
            try await self.importLinkSnapshot(data)
        }
        // A sign-out wipe that was cut short (kill, failure) is finished first; the sign-in below waits for it.
        if mode.isLive {
            wipeTask = Task { [weak self] in
                do { try await LiveMessaging.finishPendingWipe() } catch { self?.wipeFailed = true }
            }
        }
        if live?.isSignedIn == true { Task { await didSignIn() } }
        if let url = launch.openURL { handle(url: url) }
        chatStore.onChange = { [weak self] in self?.revision += 1 }
        restartExpirySweeper()
        makePeople(repo: nil, channel: nil, scripted: sampleData && launch.scriptedTyping)
        lock.onHeldChatReleased = { [weak self] id in self?.openChatID = id }
        // Switching the lock on hides the names and text of notifications already shown.
        lock.onLockSwitchedOn = { Task { await NotificationActions.shared.hideDeliveredContent() } }
        sampleCalls?.onChange = { [weak self] state in self?.callStateChanged(state) }
        Task { await refreshDevices() }
        #if os(macOS)
        macRinger = MacRinger(handler: self, systemUI: !launch.isUITesting)
        #endif
        NotificationActions.shared.attach(self)
        if mode.isLive {
            #if os(iOS)
            setUpVoIP()
            #endif
            startPathMonitor()
        }
    }

    /// The sample fake and its in-memory settings exist only in sample-data mode (`-sampleData`, never a live account);
    /// a live session always gets the system prompt and the app group's defaults (the notification extension reads the
    /// switch there).
    /// The app group's defaults when there is one (the notification extension reads the lock switch there).
    private static func lockDefaults() -> UserDefaults {
        let group = (Bundle.main.object(forInfoDictionaryKey: "AppGroupID") as? String)?.trimmingCharacters(in: .whitespaces)
        return group.flatMap { $0.isEmpty ? nil : UserDefaults(suiteName: $0) } ?? .standard
    }

    private func savePrivacySwitches(_ old: UserSettings) {
        guard let privacyStore, settings.typingIndicator != old.typingIndicator
                || settings.onlineAndLastSeen != old.onlineAndLastSeen || settings.readReceipts != old.readReceipts else { return }
        privacyStore.save(PrivacySwitches(
            typingIndicator: settings.typingIndicator, onlineAndLastSeen: settings.onlineAndLastSeen,
            readReceipts: settings.readReceipts))
    }

    /// For the extension files: `revision` can only be written here.
    func bumpRevision() { revision += 1 }

    private static func makeLock(sampleData: Bool, launch: LaunchOptions) -> AppLock {
        #if os(iOS)
        let phone = true
        #else
        let phone = false
        #endif
        if sampleData {
            let auth = SampleDeviceAuth(results: launch.fakeAuthResults)
            return AppLock(store: MemoryLockSettingsStore(), auth: auth, coversWhenInactive: phone, inactiveCountsAsLeaving: !phone)
        }
        return AppLock(store: DefaultsLockSettingsStore(defaults: lockDefaults()), auth: SystemDeviceAuth(),
                       coversWhenInactive: phone, inactiveCountsAsLeaving: !phone)
    }

    /// App lock on or off, a plain non-blocking read for the notifications (A5). Unknown counts as on.
    var appLockOn: Bool { lock.appLockOn }

    /// The Privacy switch: needs one passed prompt. Mirrors the result into `settings`.
    func setAppLock(_ on: Bool) async -> LockSwitchOutcome {
        let outcome = await lock.requestSetEnabled(on, reason: String(localized: "Unlock \(AppBrand.name)"))
        if outcome == .changed {
            settings.appLock = lock.appLockOn
            NotificationActions.shared.storeChanged()   // the notification extension reads the new setting
        }
        return outcome
    }

    func setLockAfter(_ seconds: Int) {
        lock.setLockAfter(seconds)
        settings.lockAfterSeconds = lock.lockAfterSeconds
    }

    /// A chat that something outside the screens wants open (a notification tap): kept while the app is locked and
    /// opened once, after unlock.
    func openChatFromOutside(_ chatID: String) {
        if let id = lock.openChatWhenUnlocked(chatID) { openChatID = id }
    }

    private func callStateChanged(_ state: CallState?) {
        activeCall = state
        // The socket was kept open for the call while the app was in the background; close it now.
        if isBackground, state?.isEnded ?? true { Task { await stopLive() } }
    }

    // MARK: - Chats (every read touches `revision` so SwiftUI tracks store changes)

    var folders: [ChatFolder] { _ = revision; return chatStore.folders }

    func chats(in folder: ChatFolder) -> [Chat] {
        _ = revision
        return chatStore.chats(in: folder)
    }

    /// Chats in a folder whose name or messages match `query`.
    func chats(in folder: ChatFolder, matching query: String) -> [Chat] {
        var chats = chats(in: folder)
        let query = query.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return chats }
        // Search covers archived chats too (Android's does): they follow the main list's matches.
        chats += chatStore.archivedChats.filter { chat in
            switch folder.kind {
            case .all: true
            case .unread: chat.unreadCount > 0
            case .custom: chat.folderIDs.contains(folder.id)
            }
        }
        // Message matches come from the store's search (the whole history), not only the loaded messages.
        let messageChatIDs = Set(chatStore.searchMessages(query, limit: 200).map(\.chatID))
        return chats.filter { chat in
            let name = chat.title ?? chat.contactID.flatMap { chatStore.contact(id: $0) }?.displayName ?? ""
            return name.localizedCaseInsensitiveContains(query) || messageChatIDs.contains(chat.id)
        }
    }

    func folderChipTitle(_ folder: ChatFolder) -> String { _ = revision; return chatStore.folderChipTitle(folder) }
    var messageRequests: [Chat] { _ = revision; return chatStore.messageRequests }
    var messageRequestsRowTitle: String? { _ = revision; return chatStore.messageRequestsRowTitle }
    var contacts: [Contact] { _ = revision; return chatStore.contacts }
    func chat(id: String) -> Chat? { _ = revision; return chatStore.chat(id: id) }
    func contact(id: String) -> Contact? { _ = revision; return chatStore.contact(id: id) }
    /// A group chat has no contact: its `contactID` is `nil`, and so is the answer.
    func contact(id: String?) -> Contact? { id.flatMap { contact(id: $0) } }
    func isContact(_ id: String) -> Bool { contacts.contains { $0.id == id } }

    func send(_ content: MessageContent, in chatID: String) { chatStore.send(content, in: chatID); revision += 1 }
    func retrySend(messageID: String) { chatStore.retrySend(messageID: messageID); revision += 1 }
    /// Asks the server for its attachment limit once (`GET /v1/server`); the size check for videos uses it.
    func refreshAttachmentLimit() async {
        guard serverAttachmentLimit == nil, !isSampleData, let client = live?.client,
              let info = try? await client.serverInfo(), info.limits.attachmentBytes > 0 else { return }
        serverAttachmentLimit = Int64(info.limits.attachmentBytes)
    }
    /// Cancel on an outgoing attachment that is still uploading.
    func cancelUpload(messageID: String, in chatID: String) { chatStore.cancelUpload(messageID: messageID, in: chatID); revision += 1 }
    /// Photos and videos from the preview screen; the caption follows the first as a text message.
    func sendMedia(_ items: [OutgoingMedia], caption: String, in chatID: String) {
        chatStore.sendMedia(items, caption: caption, in: chatID)
        revision += 1
    }
    func markRead(chatID: String) {
        chatStore.markRead(chatID: chatID)
        revision += 1
        NotificationActions.shared.removeDelivered(chatID: chatID)
    }
    func accept(requestID: String) { chatStore.accept(requestID: requestID); revision += 1 }
    func block(contactID: String) {
        chatStore.block(contactID: contactID)
        revision += 1
        NotificationActions.shared.removeDelivered(chatID: contactID)
    }
    func report(chatID: String) { chatStore.report(chatID: chatID); revision += 1 }
    /// False until the engine gives real member keys; chat info then hides Verify and the Verified seal.
    var supportsSafetyNumbers: Bool { _ = revision; return chatStore.supportsSafetyNumbers }
    func safetyNumber(for chatID: String) -> SafetyNumber? { _ = revision; return chatStore.safetyNumber(for: chatID) }
    func setVerified(_ verified: Bool, chatID: String) { chatStore.setVerified(verified, chatID: chatID); revision += 1 }
    /// True when one of the chat's groups could not be read; chat info then says so and hides Verify.
    func membersUnreadable(for chatID: String) -> Bool { _ = revision; return chatStore.membersUnreadable(for: chatID) }

    @discardableResult
    func addContact(username: String) async -> Contact? {
        defer { revision += 1 }
        return await chatStore.addContact(username: username)
    }

    /// The chat with this contact, created if needed.
    func openChat(with contactID: String) -> String {
        defer { revision += 1 }
        return chatStore.ensureChat(with: contactID)
    }

    /// Makes a group chat (New chat > New group).
    func createGroup(accounts: [String], name: String, photo: Data?) async -> GroupCreateOutcome {
        defer { revision += 1 }
        return await chatStore.createGroup(accounts: accounts, name: name, photo: photo)
    }

    /// What a chat is called: a group's name, else its contact's name. A group whose name has not arrived yet is "Group".
    func chatName(_ chat: Chat) -> String {
        _ = revision
        if let title = chat.title { return title }
        if let name = contact(id: chat.contactID)?.displayName { return name }
        return ChatID.isGroup(chat.id) ? String(localized: "Group") : ""
    }

    func chatID(for contactID: String) -> String? {
        _ = revision
        return (chatStore.chats(in: .all) + chatStore.archivedChats + chatStore.messageRequests).first { $0.contactID == contactID }?.id
    }

    /// Opens search for `text`: the search tab on iOS 26, otherwise the chat list's own field.
    func searchChats(for text: String) {
        #if os(iOS)
        if #available(iOS 26, *) {
            searchText = text
            selectedTab = .search
            return
        }
        #endif
        chatListQuery = text
        selectedTab = .chats
    }

    // MARK: - Folders

    @discardableResult
    func addFolder(name: String, chatIDs: Set<String>) -> ChatFolder {
        defer { revision += 1 }
        return chatStore.addFolder(name: name, chatIDs: chatIDs)
    }
    func renameFolder(id: String, to name: String) { chatStore.renameFolder(id: id, to: name); revision += 1 }
    func deleteFolder(id: String) {
        chatStore.deleteFolder(id: id)
        if selectedFolder.id == id { selectedFolder = .all }
        revision += 1
    }
    func moveFolder(id: String, toCustomIndex index: Int) { chatStore.moveFolder(id: id, toCustomIndex: index); revision += 1 }

    // MARK: - Account and devices

    var me: Contact { _ = revision; return chatStore.me }
    /// The own name as stored, "" when none is: what the editor starts with (never the placeholder "You").
    var myStoredName: String { _ = revision; return chatStore.myStoredName }

    /// Saves the person's own name: shown at once, sent with `PATCH /v1/me/settings`. When the server does not take
    /// it the stored name comes back and the error is returned. A name `DisplayNameRule` refuses never leaves the
    /// device. Nothing is written for a session that has ended meanwhile. `nil` when saved, ignored (another account
    /// action is running) or cancelled.
    func saveDisplayName(_ input: String) async -> AccountError? {
        guard let name = DisplayNameRule.clean(input) else { return .server }
        guard accountGate.begin(.savingName) else { return nil }
        defer { accountGate.finish() }
        let store = chatStore
        let generation = sessionGeneration
        func current() -> Bool { sessionGeneration == generation && chatStore === store }
        let previous = store.myStoredName   // "" when none is stored: never the placeholder
        store.setMyDisplayName(name)
        revision += 1
        guard !isSampleData else { return nil }
        guard let client = live?.client else {
            store.setMyDisplayName(previous)
            revision += 1
            return .server
        }
        do throws(ServerError) {
            try await client.updateSettings(displayName: name, findByEmail: nil)
            return nil
        } catch {
            if current() { store.setMyDisplayName(previous); revision += 1 }
            if error.code == "cancelled" { return nil }
            return error.code == "network" ? .network : .server
        }
    }

    /// Reads the own name from the server once when a session opens and stores it only when none is stored. Silent on
    /// failure; writes nothing when the person signed out or the session changed meanwhile.
    private func seedOwnName() async {
        guard !isSampleData, let client = live?.client else { return }
        let store = chatStore
        let generation = sessionGeneration
        guard store.myStoredName.isEmpty, let me = try? await client.me() else { return }
        let same = sessionGeneration == generation && chatStore === store && live?.isSignedIn == true
        guard let name = OwnNameSeed.nameToStore(stored: store.myStoredName, fetched: me.displayName, sameSession: same)
        else { return }
        store.setMyDisplayName(name)
        revision += 1
    }
    var linkedDevices: [DeviceInfo] = []

    func refreshDevices() async {
        if let list = try? await account.devices() { linkedDevices = list }
    }

    func removeDevice(id: String) async {
        try? await account.removeDevice(id: id)
        await refreshDevices()
        // The server tells the other devices; this one runs reconcile itself (the scheduler skips this device's id).
        if !linkedDevices.contains(where: { $0.id == id }) { await messaging?.reconciler.deviceRevoked(id) }
    }

    /// Deletes the account on the server, then returns to the welcome screen.
    /// Returns the error to show when the server could not delete it.
    func deleteAccount() async -> AccountError? {
        guard accountGate.begin(.deleting) else { return nil }   // another account action is running
        defer { accountGate.finish() }
        await registrar?.unregisterAll()
        do throws(AccountError) {
            try await account.deleteAccount()
        } catch {
            return error
        }
        await stopLive()
        resetToWelcome()
        return nil
    }

    /// "Sign out": removes this device from the account on the server, then wipes the local data like a deleted
    /// account and returns to the welcome screen. With no connection (or any failure of the removal) nothing is
    /// wiped and the person stays signed in, so they can try again; the result says which.
    func signOut() async -> SignOutDecision {
        guard accountGate.begin(.signingOut) else { return .failed }   // another account action is running
        defer { accountGate.finish() }
        guard signOutGate.begin() else { return .failed }   // a second tap: the first one decides what is shown
        guard let live, !isSampleData else {
            // Sample data has no server.
            account.signOutLocally()
            signOutGate.finished()
            resetToWelcome()
            return .wipe
        }
        var failure: AccountError?
        if let deviceID = sessions?.current?.deviceID ?? linkedDevices.first(where: \.isThisDevice)?.id {
            do throws(AccountError) { try await account.removeDevice(id: deviceID) } catch { failure = error }
        }   // no device ID: nothing to remove on the server, sign out locally
        let decision = SignOutDecision.after(revokeError: failure, sessionEndedMeanwhile: signOutGate.sessionEndedMeanwhile)
        guard decision == .wipe else {
            signOutGate.failed()
            return decision
        }
        // Like `serverEndedSession`: `resetToWelcome` stops the live graph (forced) and wipes; the wipe marker is
        // durable before anything suspends. A user-confirmed sign-out always wipes.
        live.signOutLocally()
        signOutGate.finished()
        resetToWelcome(revoked: true)
        return .wipe
    }

    /// Lifts a block: the contact can write to this person again. Local at once; the server is told in the background.
    func unblock(contactID: String) { chatStore.unblock(contactID: contactID); revision += 1 }

    /// Reads the "Find me by email" switch from the server, so the toggle shows what the server has. A value the
    /// person changed while this was loading wins.
    func loadFindByEmail() async {
        guard !isSampleData, let client = live?.client else { return }
        findByEmailChanged = false
        guard let me = try? await client.me(), !findByEmailChanged, !savingFindByEmail else { return }
        if settings.findByEmail != me.findByEmail { settings.findByEmail = me.findByEmail }
    }

    /// Sends "Find me by email" to the server. On failure the switch goes back and the error is returned; `nil` when
    /// it saved, when a save is already running (the change is ignored) or when the request was cancelled.
    func setFindByEmail(_ on: Bool) async -> AccountError? {
        guard !savingFindByEmail else { return nil }
        let previous = settings.findByEmail
        findByEmailChanged = true
        settings.findByEmail = on
        guard !isSampleData, let client = live?.client else { return nil }
        savingFindByEmail = true
        defer { savingFindByEmail = false }
        do throws(ServerError) {
            try await client.updateSettings(displayName: nil, findByEmail: on)
            return nil
        } catch {
            settings.findByEmail = previous
            if error.code == "cancelled" { return nil }
            return error.code == "network" ? .network : .server
        }
    }

    /// `revoked: false` (a sign-out the server did not prove a revocation, ruling 2026-10-28) stops the old graph and
    /// keeps its data when it records its device; the next sign-in, always a new device, wipes it before opening it
    /// (`LocalAccountData`). Steps and their order: `SignOutPlan`.
    private func resetToWelcome(revoked: Bool = true) {
        sessionGeneration += 1
        AppLinks.coordinator.clear()
        // Drafts and the pending in-chat search belong to the account that is leaving.
        ChatScreenState.shared.reset()
        // App lock goes back to off with its default delay; a prompt still open changes nothing.
        lock.reset()
        settings.appLock = false
        settings.lockAfterSeconds = AppLockPolicy.defaultSeconds
        // Decoded pictures and plaintext copies made for previews and sends belong to it too.
        MediaPreview.removeTemporaryFolders()
        VoiceSession.removeOrphans()
        Task { await ThumbnailLoader.shared.clear(); await VideoPoster.shared.clear() }
        MediaFileCheck.shared.clear()
        // The live store belongs to the signed-out account. An empty store stands in until the next sign-in.
        if !isSampleData {
            let old = messaging
            // A running graph passed `LocalAccountData.prepare`, which records the device.
            let steps = SignOutPlan.steps(
                revoked: revoked, deviceRecorded: old != nil || LiveMessaging.hasRecordedDevice())
            if steps.first == .markWipePending, let container = LiveMessaging.container {
                // Durable first: if the app is killed or a step fails, the next launch (or the next sign-in) wipes.
                try? LocalAccountData.markWipePending(in: container)
            }
            wipeTask = Task { [previous = wipeTask] in
                await previous?.value
                // The old graph stops first: no event task, send or sync can write after the wipe.
                await stopLive(force: true)
                guard steps.contains(.wipe) else {
                    await old?.stop()
                    return
                }
                do {
                    if let old { try await old.wipeAfterSignOut() } else { try await LiveMessaging.finishPendingWipe() }
                } catch {
                    // Tried `DeviceSync.wipeAttempts` times. Stop all further work; the pending marker stays and the
                    // next launch retries before any data is opened.
                    print("wipe_failed")
                    self.wipeFailed = true
                }
            }
        }
        if messaging != nil {
            messaging = nil
            makePeople(repo: nil, channel: nil)
            callSealer.clear()
            ProfileImageCache.clear()   // the signed-out account's photos must not outlive it
            let empty = AppStores.signedOutStore(sampleData: isSampleData)
            empty.onChange = { [weak self] in self?.revision += 1 }
            chatStore = empty
            restartExpirySweeper()
            revision += 1
            NotificationActions.shared.signedOut()
        }
        // The privacy switches belong to the account that left: the next one starts with them on. Set after the
        // signal model is gone, so turning them back on here sends nothing.
        settings.typingIndicator = true
        settings.onlineAndLastSeen = true
        settings.readReceipts = true
        settings.linkPreviews = false
        #if os(macOS)
        onboarding = OnboardingFlow(platform: .mac)
        #else
        onboarding = OnboardingFlow(platform: .iPhone)
        #endif
        selectedTab = .chats
        linkedDevices = []
    }

    /// The server ended this session. Only a proven revocation (a 4003 close, or a 401 that `GET /v1/me`
    /// confirms) wipes and says the device was removed; any other 401 only signs out (ruling 2026-10-28).
    private func serverEndedSession(_ reason: SignOutReason) {
        guard signOutGate.serverEndedSession() else { return }
        Task { await stopLive() }
        resetToWelcome(revoked: reason == .revoked)
        if reason == .revoked { deviceRemovedAlert = true }
    }

    // MARK: - Signed-in services

    /// Call after any sign-in succeeds: loads devices, starts the live connection and registers for push.
    func didSignIn() async {
        signOutGate.didSignIn()
        await wipeTask?.value
        guard !wipeFailed else { return }
        await refreshDevices()
        await installLiveStore()
        await startLive()
        // Not awaited: offline, the read would hold up the inbox sync and key top-up below until it times out.
        // The seed's session, store and signed-in guard makes a late write safe.
        Task { await seedOwnName() }
        await syncInbox()
        await replenishKeyPackages()
        await registerForPushIfAllowed()
    }

    /// A sweeper for the current store; the old one stops. It runs now unless an iPhone app is in the background.
    private func restartExpirySweeper() {
        expirySweeper?.stop()
        let sweeper = ExpirySweeper(store: chatStore) { ids in NotificationActions.shared.removeDelivered(messageIDs: ids) }
        expirySweeper = sweeper
        #if os(iOS)
        if isBackground { return }
        #endif
        sweeper.start()
    }

    /// iPhone: the socket runs only while the app is active. Mac: whenever the app runs.
    func scenePhaseChanged(_ phase: ScenePhase) {
        peopleScenePhase(phase)
        #if os(iOS)
        switch phase {
        case .active:
            isBackground = false
            expirySweeper?.start()
            Task {
                await startLive()
                await syncInbox()
                await replenishKeyPackages()
            }
        case .background:
            isBackground = true
            expirySweeper?.stop()
            // A running call keeps its signaling; `callStateChanged` closes the socket when the call ends.
            if activeCall?.isEnded == false { return }
            Task { await stopLive() }
        default: break
        }
        #endif
    }

    /// Swaps the sample store for the real one when this device is signed in and the messaging stack could be
    /// built. One escrow key provider is shared inside `LiveMessaging.make`.
    private func installLiveStore() async {
        await wipeTask?.value
        wipeTask = nil
        guard !wipeFailed, !isSampleData, messaging == nil, let session = sessions?.current, let client = live?.client
        else { return }
        guard let built = await LiveMessaging.make(
            session: session, client: client, network: network, settings: settings.autoDownload,
            readReceipts: settings.readReceipts) else {
            // Never fall back to sample chats: the chat list stays empty and the error offers a retry.
            liveStoreUnavailable = true
            return
        }
        liveStoreUnavailable = false
        messaging = built
        makePeople(repo: built.repo, channel: built.ephemeral)
        callSealer.use(built.store)
        built.store.onChange = { [weak self] in self?.revision += 1 }
        chatStore = built.store
        restartExpirySweeper()
        revision += 1
    }

    /// The new device's side of linking, called by the account service once the linked session is saved and
    /// before anything syncs: builds the live store and copies the snapshot's contacts, chats, folders, blocks and
    /// settings into it, in one transaction (importing it again adds nothing). Throws when there is no live store or
    /// the snapshot cannot be read; the device then starts without its contacts and no error is shown.
    private func importLinkSnapshot(_ data: Data) async throws {
        await installLiveStore()
        guard let messaging else { throw LinkSnapshotError.noLiveStore }
        try await messaging.sync.importSnapshot(data)
        messaging.store.refresh()
        revision += 1
    }

    /// The approving device's snapshot; empty (the new device then starts without contacts) with no live store.
    fileprivate func exportLinkSnapshot() async -> Data {
        guard let messaging else { return Data() }
        return (try? await messaging.sync.exportSnapshot()) ?? Data()
    }

    /// Fetches and processes the inbox (a pushed item, or catching up after the socket was away).
    private func syncInbox() async {
        await messaging?.store.syncInbox()
    }

    /// Tops up this device's KeyPackages once the inbox is in step; retried at the next start or foreground, so a
    /// failure is not reported. (Adding and removing my own devices in my groups is the reconciler's, on connect.)
    private func replenishKeyPackages() async {
        guard let sync = messaging?.sync else { return }
        try? await sync.replenishKeyPackages()
    }

    private func startLive() async {
        ensureConnection()
        await connection?.start()
    }

    /// Creates the live connection and the call engine on top of it, once per sign-in.
    private func ensureConnection() {
        guard !wipeFailed, connection == nil, let live, live.isSignedIn, let client = live.client else { return }
        // The call engine reads chats from the live store and seals through it: no live store, no engine (never on
        // the sample or empty store).
        guard messaging != nil else { return }
        let c = LiveConnection(client: client, transport: URLSessionWebSocketTransport())
        connection = c
        messaging?.ephemeralSender.use(c)
        let signaling = LiveCallSignaling(connection: c, client: client)
        // A VoIP push can wake a suspended iPhone whose socket was stopped: every call request first starts it.
        let gated = GatedCallSignaling(base: signaling, connection: AppCallConnection(
            isConnected: { [weak self] in await self?.connectionState == .connected },
            connect: { [weak self] in if let self { await self.startLive() } }))
        let engine = LiveCallEngine(chat: chatStore, signaling: gated, sealer: callSealer,
                                    server: server, settings: settings, network: network)
        engine.onChange = { [weak self] state in self?.callStateChanged(state) }
        engine.onStats = { [weak self] stats in self?.callStats = stats }
        engine.onHistory = { [weak self] in self?.revision += 1 }
        #if os(iOS)
        engine.callKit = callKit
        engine.voip = voipCenter
        voipCenter?.handler = engine
        voipCenter?.engine = engine
        #else
        engine.ringer = macRinger
        #endif
        liveCalls = engine
        // Pushes that woke the app before this point can now be opened and handed to the engine.
        Task { await callReady.open() }
        liveTasks = [
            Task { [weak self] in
                for await event in c.events {
                    signaling.ingest(event)   // call events go to the engine; the rest are handled here
                    switch event {
                    case .deviceRevoked(let id): await self?.deviceRevoked(id)
                    case .inbox: await self?.syncInbox()
                    case .conversationEphemeral: self?.messaging?.ephemeral.ingest(event)
                    default: break
                    }
                }
            },
            Task { [weak self] in
                // `.revoked` also reaches the client's sign-out path, which calls `serverEndedSession`.
                for await state in c.states {
                    await self?.setConnectionState(state)
                    switch state {
                    // Detached: `stopLive` awaits every live task, this one included.
                    case .revoked: Task { await self?.stopLive() }
                    case .connected: await self?.didConnect()
                    default: break
                    }
                }
            },
        ]
    }

    private func setConnectionState(_ state: ConnectionState) { connectionState = state }

    private func startPathMonitor() {
        let reachability = NetworkReachability()
        reachability.onChange = { [weak self] satisfied in self?.hasNetwork = satisfied }
        reachability.start()
        self.reachability = reachability
        let monitor = PathMonitor()
        monitor.onChange = { [weak self] network, meaningful in
            guard let self else { return }
            if self.network != network { self.network = network }
            self.liveCalls?.networkChanged(network, meaningful: meaningful)
        }
        monitor.start()
        pathMonitor = monitor
    }

    #if os(iOS)
    /// One PushKit registry and one CallKit provider for the app's lifetime (iPhone, signed-in builds only).
    private func setUpVoIP() {
        let callKit = CallKitController()
        let resolver = VoIPPushResolver(
            sealer: callSealer,
            contact: { [weak self] id in await self?.contact(id: id) },
            status: { [weak self] id in await self?.callStatus(id) },
            ready: callReady)   // a push that woke the app waits (bounded) for sign-in to finish
        let center = VoIPPushCenter(callKit: callKit, resolver: resolver,
                                    onToken: { [weak self] token in Task { @MainActor in await self?.didReceiveVoIPToken(token) } })
        self.callKit = callKit
        voipCenter = center
        center.start()
    }

    private func didReceiveVoIPToken(_ token: Data) async {
        voipToken = token
        if let registrar { await registrar.didRegister(voipToken: token) }
    }
    #endif

    /// Every live connect: catch up on the inbox, then bring this account's devices in every chat in line with
    /// the device list (mls-profile "Removing a device"); the scheduler runs it once more 11 minutes later.
    private func didConnect() async {
        peopleConnected()
        await syncInbox()
        await messaging?.reconciler.connected()
    }

    /// A `device.revoked` event (unsigned). For another device it only runs reconcile; one naming this device is
    /// ignored. Only a 4003 or a confirmed 401 (revoked session) wipes this device, through `onSignedOut`.
    private func deviceRevoked(_ id: String) async {
        await refreshDevices()
        await messaging?.reconciler.deviceRevoked(id)
    }

    func callStatus(_ callID: String) async -> CallStatus? {
        guard let signaling = liveCalls?.signaling else { return nil }
        return try? await signaling.status(callID: callID)
    }

    private func stopLive(force: Bool = false) async {
        await people?.flush()   // the `away` signal goes out before the socket closes
        let signedOut = force || live?.isSignedIn != true
        if signedOut {
            await callReady.close()
            // While the connection is still up: hang up a running call, close its media, let every effect run.
            await liveCalls?.shutdown()
        }
        await messaging?.reconciler.disconnected()
        await connection?.stop()
        connectionState = .idle
        if signedOut {   // drop the connection and its listeners
            let tasks = liveTasks
            tasks.forEach { $0.cancel() }
            for task in tasks { await task.value }   // none can touch the store afterwards
            liveTasks = []
            connection = nil
            messaging?.ephemeralSender.use(nil)
            liveCalls = nil
        }
    }

    /// The "Try Again" of the live store error.
    func retryLiveStore() async {
        liveStoreUnavailable = false
        await didSignIn()
    }

    /// Asks for a push token when notifications are already allowed (the Permissions step asks first).
    func registerForPushIfAllowed() async {
        guard !isSampleData, !isUITesting else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        #if os(iOS)
        UIApplication.shared.registerForRemoteNotifications()
        #else
        NSApplication.shared.registerForRemoteNotifications()
        #endif
    }

    /// From the app delegate. Push stays off without a gateway key, or when the server's gateway differs.
    func didReceivePushToken(_ token: Data) async {
        guard let live, live.isSignedIn, let client = live.client, let key = config.pushGatewayKey else { return }
        apnsToken = token
        guard let info = try? await client.serverInfo(), info.push?.gateway == config.pushGatewayURL else { return }
        let registrar = PushRegistrar(client: client, gatewayKey: key, environment: config.apnsEnvironment,
                                      bundleID: Bundle.main.bundleIdentifier ?? "")
        self.registrar = registrar
        await registrar.didRegister(alertToken: token)
        #if os(iOS)
        if let voipToken { await registrar.didRegister(voipToken: voipToken) }
        #endif
    }

    // MARK: - Calls

    var stripLabel: String { DataModePolicy.stripLabel(server: server, settings: settings.call, network: network) }

    func startCall(contactID: String, video: Bool) {
        guard let contact = chatStore.contact(id: contactID) else { return }
        calls.start(with: contact, video: video)
    }

    /// What the call info sheet shows: live numbers, or steady sample numbers in `-sampleData` mode.
    var callInfoStats: MediaStats {
        if let callStats { return callStats }
        return isSampleData ? MediaStats(roundTripTime: 0.062, inboundLossFraction: 0.004, usingRelay: false) : MediaStats()
    }

    func dismissEndedCall() {
        guard activeCall?.isEnded == true else { return }
        calls.reset()
        activeCall = nil
    }

    // MARK: - Links

    func handle(url: URL) {
        if let invite = InviteLink.parse(url) {
            pendingInvite = invite
        } else {
            // `/u/<name>` and `/c/<token>` of this server: kept until the app can open them (see `AppModel+Links`).
            receiveContactLink(url)
        }
    }

    /// The signed-in connection, for the contact-link calls in `AppModel+Links`.
    var contactLinkClient: ServerClient? { live?.client }
}

private enum LinkSnapshotError: Error { case noLiveStore }

/// The account service is built before `AppModel`, so its snapshot export reaches the model through this.
@MainActor private final class LinkSnapshotBridge {
    weak var model: AppModel?
    func export() async -> Data { await model?.exportLinkSnapshot() ?? Data() }
}

/// Launch arguments used by UI tests: `-skipOnboarding`, `-uiTesting`, `-network mobile|wifi`, `-openURL <url>`, `-sampleData`, `-fakeAuth success,cancel,...`.
struct LaunchOptions {
    var skipOnboarding = false
    /// Skips system permission prompts so UI tests aren't blocked by alerts.
    var isUITesting = false
    var network: NetworkType = .mobile
    var openURL: URL?
    /// `-sampleData`: sample accounts and chats, no server. UI tests always pass it.
    var sampleData = false
    /// `-fakeAuth success,cancel,...`: what the sample fake answers to the next lock prompts (then success).
    var fakeAuthResults: [AuthResult] = []
    /// `-scriptedTyping`: the sample chat with Riya shows "typing…" for about ten seconds, then her message arrives.
    var scriptedTyping = false
    /// `-sampleMemberGroup`: the sample chats include a group where the person is a plain member.
    var memberGroup = false

    static var current: LaunchOptions {
        let args = ProcessInfo.processInfo.arguments
        var options = LaunchOptions()
        options.skipOnboarding = args.contains("-skipOnboarding")
        options.isUITesting = args.contains("-uiTesting")
        options.sampleData = args.contains("-sampleData")
        options.scriptedTyping = args.contains("-scriptedTyping")
        options.memberGroup = args.contains("-sampleMemberGroup")
        if let i = args.firstIndex(of: "-network"), i + 1 < args.count {
            options.network = args[i + 1] == "wifi" ? .wifi : .mobile
        }
        if let i = args.firstIndex(of: "-fakeAuth"), i + 1 < args.count {
            options.fakeAuthResults = args[i + 1].split(separator: ",").map { (word: Substring) -> AuthResult in word == "cancel" ? .cancelled : .success }
        }
        if let i = args.firstIndex(of: "-openURL"), i + 1 < args.count {
            options.openURL = URL(string: args[i + 1])
        }
        return options
    }
}
