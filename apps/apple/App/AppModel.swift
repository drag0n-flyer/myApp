import AppCore
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
    /// `SampleChatStore` with `-sampleData` (and until the real messaging stack is available); `LiveChatStore`
    /// once this device is signed in and the stack could be built (`installLiveStore`).
    private(set) var chatStore: any ChatStore
    let calls: SimulatedCallEngine
    let account: any AccountService
    /// `nil` in `-sampleData` mode.
    private let live: LiveAccountService?
    let config = AppConfig.current
    let isSampleData: Bool
    /// Shown after the server ends this device's session.
    var deviceRemovedAlert = false
    private var connection: LiveConnection?
    private let sessions: SessionStore?
    private var messaging: LiveMessaging?
    /// The wipe of the last signed-out account; the next sign-in waits for it, so it never opens half-deleted data.
    private var wipeTask: Task<Void, Never>?
    private var liveTasks: [Task<Void, Never>] = []
    private var apnsToken: Data?
    private var registrar: PushRegistrar?

    var server: ServerPolicy {
        didSet { calls.server = server }
    }
    var settings: UserSettings {
        didSet {
            calls.settings = settings.call
            messaging?.autoDownload.value = settings.autoDownload
        }
    }
    var network: NetworkType {
        didSet {
            calls.setNetwork(network)
            messaging?.network.value = network
        }
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
    private(set) var activeCall: CallState?
    var pendingInvite: InviteLink?
    /// Mac only: a contact who is ringing (macOS has no CallKit).
    var incomingCaller: Contact?
    #if os(macOS)
    @ObservationIgnored private(set) var macRinger: MacRinger?
    #endif
    let isUITesting: Bool

    /// Bumped after every store change so views that read store data refresh.
    private(set) var revision = 0

    init(launch: LaunchOptions = .current) {
        let config = AppConfig.current
        #if os(macOS)
        let platform = DevicePlatform.mac
        let deviceName = Host.current().localizedName ?? "Mac"
        #else
        let platform = DevicePlatform.iPhone
        let deviceName = UIDevice.current.name
        #endif
        let account: any AccountService
        var live: LiveAccountService?
        var sessionStore: SessionStore?
        if launch.sampleData {
            account = SampleAccountService(platform: platform)
        } else {
            let sessions = SessionStore(storage: KeychainSessionStorage(accessGroup: config.keychainAccessGroup))
            let service = LiveAccountService(
                defaultHost: config.defaultServerHost, passkeyDomains: config.passkeyDomains, platform: platform,
                deviceName: deviceName, sessions: sessions,
                makeClient: { ServerClient(host: $0, session: sessions) },
                passkeys: PasskeyAuthenticator(),
                approvalReason: String(localized: "Confirm to approve this new device."))
            account = service
            live = service
            sessionStore = sessions
        }
        let server = account.defaultServer
        let settings = UserSettings.initial(from: server)
        self.account = account
        self.live = live
        self.sessions = sessionStore
        self.isSampleData = launch.sampleData
        self.server = server
        self.settings = settings
        self.network = launch.network
        self.isUITesting = launch.isUITesting
        let chatStore = SampleChatStore()
        self.chatStore = chatStore
        self.calls = SimulatedCallEngine(server: server, settings: settings.call, network: launch.network)
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
        live?.onSignedOut = { [weak self] in self?.deviceWasRemoved() }
        // A sign-out wipe that was cut short (kill, failure) is finished first; the sign-in below waits for it.
        if !launch.sampleData {
            wipeTask = Task { try? await LiveMessaging.finishPendingWipe() }
        }
        if live?.isSignedIn == true { Task { await didSignIn() } }
        if let url = launch.openURL { handle(url: url) }
        chatStore.onChange = { [weak self] in self?.revision += 1 }
        calls.onChange = { [weak self] state in
            self?.activeCall = state
        }
        Task { await refreshDevices() }
        #if os(macOS)
        macRinger = MacRinger(handler: self, systemUI: !launch.isUITesting)
        #endif
    }

    // MARK: - Chats (every read touches `revision` so SwiftUI tracks store changes)

    var folders: [ChatFolder] { _ = revision; return chatStore.folders }

    func chats(in folder: ChatFolder) -> [Chat] {
        _ = revision
        return chatStore.chats(in: folder)
    }

    /// Chats in a folder whose name or messages match `query`.
    func chats(in folder: ChatFolder, matching query: String) -> [Chat] {
        let chats = chats(in: folder)
        let query = query.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return chats }
        // Message matches come from the store's search (the whole history), not only the loaded messages.
        let messageChatIDs = Set(chatStore.searchMessages(query, limit: 200).map(\.chatID))
        return chats.filter { chat in
            let name = chatStore.contact(id: chat.contactID)?.displayName ?? ""
            return name.localizedCaseInsensitiveContains(query) || messageChatIDs.contains(chat.id)
        }
    }

    func folderChipTitle(_ folder: ChatFolder) -> String { _ = revision; return chatStore.folderChipTitle(folder) }
    var messageRequests: [Chat] { _ = revision; return chatStore.messageRequests }
    var messageRequestsRowTitle: String? { _ = revision; return chatStore.messageRequestsRowTitle }
    var contacts: [Contact] { _ = revision; return chatStore.contacts }
    func chat(id: String) -> Chat? { _ = revision; return chatStore.chat(id: id) }
    func contact(id: String) -> Contact? { _ = revision; return chatStore.contact(id: id) }
    func isContact(_ id: String) -> Bool { contacts.contains { $0.id == id } }

    func send(_ content: MessageContent, in chatID: String) { chatStore.send(content, in: chatID); revision += 1 }
    func markRead(chatID: String) { chatStore.markRead(chatID: chatID); revision += 1 }
    func accept(requestID: String) { chatStore.accept(requestID: requestID); revision += 1 }
    func block(contactID: String) { chatStore.block(contactID: contactID); revision += 1 }
    func report(chatID: String) { chatStore.report(chatID: chatID); revision += 1 }
    /// False until the engine gives real member keys; chat info then hides Verify and the Verified seal.
    var supportsSafetyNumbers: Bool { _ = revision; return chatStore.supportsSafetyNumbers }
    func safetyNumber(for chatID: String) -> SafetyNumber? { _ = revision; return chatStore.safetyNumber(for: chatID) }
    func setVerified(_ verified: Bool, chatID: String) { chatStore.setVerified(verified, chatID: chatID); revision += 1 }

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

    func chatID(for contactID: String) -> String? {
        _ = revision
        return (chatStore.chats(in: .all) + chatStore.messageRequests).first { $0.contactID == contactID }?.id
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

    var me: Contact { chatStore.me }
    var linkedDevices: [DeviceInfo] = []

    func refreshDevices() async {
        if let list = try? await account.devices() { linkedDevices = list }
    }

    func removeDevice(id: String) async {
        try? await account.removeDevice(id: id)
        await refreshDevices()
    }

    /// Deletes the account on the server, then returns to the welcome screen.
    /// Returns the error to show when the server could not delete it.
    func deleteAccount() async -> AccountError? {
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

    private func resetToWelcome() {
        // The live store belongs to the signed-out account. The sample store stands in until the next sign-in.
        if !isSampleData, let container = LiveMessaging.container {
            // Durable first: if the app is killed or a step fails, the next launch (or the next sign-in) wipes.
            try? LocalAccountData.markWipePending(in: container)
        }
        if !isSampleData {
            let old = messaging
            wipeTask = Task { [previous = wipeTask] in
                await previous?.value
                // The old graph stops first: no event task, send or sync can write after the wipe.
                await stopLive(force: true)
                do {
                    if let old { try await old.wipeAfterSignOut() } else { try await LiveMessaging.finishPendingWipe() }
                } catch {
                    // The pending marker stays; the next launch or sign-in retries before any data is opened.
                    print("wipe_failed")
                }
            }
        }
        if messaging != nil {
            messaging = nil
            let sample = SampleChatStore()
            sample.onChange = { [weak self] in self?.revision += 1 }
            chatStore = sample
            revision += 1
        }
        #if os(macOS)
        onboarding = OnboardingFlow(platform: .mac)
        #else
        onboarding = OnboardingFlow(platform: .iPhone)
        #endif
        selectedTab = .chats
        linkedDevices = []
    }

    /// The server ended this session (401, or the device was removed from the account).
    private func deviceWasRemoved() {
        Task { await stopLive() }
        resetToWelcome()
        deviceRemovedAlert = true
    }

    // MARK: - Signed-in services

    /// Call after any sign-in succeeds: loads devices, starts the live connection and registers for push.
    func didSignIn() async {
        await refreshDevices()
        await installLiveStore()
        await startLive()
        await syncInbox()
        await registerForPushIfAllowed()
    }

    /// iPhone: the socket runs only while the app is active. Mac: whenever the app runs.
    func scenePhaseChanged(_ phase: ScenePhase) {
        #if os(iOS)
        switch phase {
        case .active: Task { await startLive() }
        case .background: Task { await stopLive() }
        default: break
        }
        #endif
    }

    /// Swaps the sample store for the real one when this device is signed in and the messaging stack could be
    /// built. One escrow key provider is shared inside `LiveMessaging.make`.
    private func installLiveStore() async {
        await wipeTask?.value
        wipeTask = nil
        guard !isSampleData, messaging == nil, let session = sessions?.current, let client = live?.client,
              let built = await LiveMessaging.make(
                session: session, client: client, network: network, settings: settings.autoDownload) else { return }
        messaging = built
        built.store.onChange = { [weak self] in self?.revision += 1 }
        chatStore = built.store
        revision += 1
    }

    /// Fetches and processes the inbox (a pushed item, or catching up after the socket was away).
    private func syncInbox() async {
        await messaging?.store.syncInbox()
    }

    private func startLive() async {
        guard let live, live.isSignedIn, let client = live.client else { return }
        if connection == nil {
            let c = LiveConnection(client: client, transport: URLSessionWebSocketTransport())
            connection = c
            liveTasks = [
                Task { [weak self] in
                    for await event in c.events {
                        switch event {
                        case .deviceRevoked(let id): await self?.deviceRevoked(id)
                        case .inbox: await self?.syncInbox()
                        default: break
                        }
                    }
                },
                Task { [weak self] in
                    // `.revoked` also reaches the client's 401 path, which calls `deviceWasRemoved`.
                    for await state in c.states {
                        switch state {
                        case .revoked: await self?.stopLive()
                        case .connected: await self?.didConnect()
                        default: break
                        }
                    }
                },
            ]
        }
        await connection?.start()
    }

    /// Every live connect: catch up on the inbox, then bring this account's devices in every chat in line with
    /// the device list (mls-profile "Removing a device"); the scheduler runs it once more 11 minutes later.
    private func didConnect() async {
        await syncInbox()
        await messaging?.reconciler.connected()
    }

    /// Another device of this account was removed: update the list and take it out of every chat. This device's
    /// own removal goes through the sign-out path instead, which stops the store before wiping.
    private func deviceRevoked(_ id: String) async {
        await refreshDevices()
        guard let messaging else { return }
        if id == messaging.deviceID {
            deviceWasRemoved()
            return
        }
        try? await messaging.sync.handleRevoked(deviceID: id)
    }

    private func stopLive(force: Bool = false) async {
        await messaging?.reconciler.disconnected()
        await connection?.stop()
        if force || live?.isSignedIn != true {   // signed out: drop the connection and its listeners
            let tasks = liveTasks
            tasks.forEach { $0.cancel() }
            for task in tasks { await task.value }   // none can touch the store afterwards
            liveTasks = []
            connection = nil
        }
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
    }

    // MARK: - Calls

    var stripLabel: String { DataModePolicy.stripLabel(server: server, settings: settings.call, network: network) }

    func startCall(contactID: String, video: Bool) {
        guard let contact = chatStore.contact(id: contactID) else { return }
        calls.start(with: contact, video: video)
    }

    func dismissEndedCall() {
        guard activeCall?.isEnded == true else { return }
        calls.reset()
        activeCall = nil
    }

    // MARK: - Links

    func handle(url: URL) {
        guard let invite = InviteLink.parse(url) else { return }
        pendingInvite = invite
    }
}

/// Launch arguments used by UI tests: `-skipOnboarding`, `-uiTesting`, `-network mobile|wifi`, `-openURL <url>`, `-sampleData`.
struct LaunchOptions {
    var skipOnboarding = false
    /// Skips system permission prompts so UI tests aren't blocked by alerts.
    var isUITesting = false
    var network: NetworkType = .mobile
    var openURL: URL?
    /// `-sampleData`: sample accounts and chats, no server. UI tests always pass it.
    var sampleData = false

    static var current: LaunchOptions {
        let args = ProcessInfo.processInfo.arguments
        var options = LaunchOptions()
        options.skipOnboarding = args.contains("-skipOnboarding")
        options.isUITesting = args.contains("-uiTesting")
        options.sampleData = args.contains("-sampleData")
        if let i = args.firstIndex(of: "-network"), i + 1 < args.count {
            options.network = args[i + 1] == "wifi" ? .wifi : .mobile
        }
        if let i = args.firstIndex(of: "-openURL"), i + 1 < args.count {
            options.openURL = URL(string: args[i + 1])
        }
        return options
    }
}
