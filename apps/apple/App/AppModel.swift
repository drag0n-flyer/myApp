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
    private var apnsToken: Data?
    private var registrar: PushRegistrar?
    /// Seals call signals through the live chat store once it is installed; fails closed before that.
    private let callSealer = SwitchableCallSealer()
    private var pathMonitor: PathMonitor?
    private var isBackground = false
    private var connectionState: ConnectionState = .idle
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
    var settings: UserSettings {
        didSet {
            syncCalls()
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
            sampleCalls?.setNetwork(network); liveCalls?.setNetwork(network)
            messaging?.network.value = network
        }
    }

    private func syncCalls() {
        sampleCalls?.server = server
        sampleCalls?.settings = settings.call
        liveCalls?.update(server: server, settings: settings)
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
        self.isSampleData = sampleData
        self.hasBrokenSetup = !setupProblems.isEmpty
        self.server = server
        self.settings = settings
        self.network = launch.network
        self.isUITesting = launch.isUITesting
        let stores = AppStores(sampleData: sampleData, server: server, settings: settings.call, network: launch.network)
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
        live?.onSignedOut = { [weak self] in self?.deviceWasRemoved() }
        // A sign-out wipe that was cut short (kill, failure) is finished first; the sign-in below waits for it.
        if mode.isLive {
            wipeTask = Task { [weak self] in
                do { try await LiveMessaging.finishPendingWipe() } catch { self?.wipeFailed = true }
            }
        }
        if live?.isSignedIn == true { Task { await didSignIn() } }
        if let url = launch.openURL { handle(url: url) }
        chatStore.onChange = { [weak self] in self?.revision += 1 }
        sampleCalls?.onChange = { [weak self] state in self?.callStateChanged(state) }
        Task { await refreshDevices() }
        #if os(macOS)
        macRinger = MacRinger(handler: self, systemUI: !launch.isUITesting)
        #endif
        if mode.isLive {
            #if os(iOS)
            setUpVoIP()
            #endif
            startPathMonitor()
        }
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
    func retrySend(messageID: String) { chatStore.retrySend(messageID: messageID); revision += 1 }
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
        // The server tells the other devices; this one runs reconcile itself (the scheduler skips this device's id).
        if !linkedDevices.contains(where: { $0.id == id }) { await messaging?.reconciler.deviceRevoked(id) }
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
        // The live store belongs to the signed-out account. An empty store stands in until the next sign-in.
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
                    // Tried `DeviceSync.wipeAttempts` times. Stop all further work; the pending marker stays and the
                    // next launch retries before any data is opened.
                    print("wipe_failed")
                    self.wipeFailed = true
                }
            }
        }
        if messaging != nil {
            messaging = nil
            callSealer.clear()
            let empty = AppStores.signedOutStore(sampleData: isSampleData)
            empty.onChange = { [weak self] in self?.revision += 1 }
            chatStore = empty
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
        await wipeTask?.value
        guard !wipeFailed else { return }
        await refreshDevices()
        await installLiveStore()
        await startLive()
        await syncInbox()
        await replenishKeyPackages()
        await registerForPushIfAllowed()
    }

    /// iPhone: the socket runs only while the app is active. Mac: whenever the app runs.
    func scenePhaseChanged(_ phase: ScenePhase) {
        #if os(iOS)
        switch phase {
        case .active:
            isBackground = false
            Task {
                await startLive()
                await syncInbox()
                await replenishKeyPackages()
            }
        case .background:
            isBackground = true
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
        callSealer.use(built.store)
        built.store.onChange = { [weak self] in self?.revision += 1 }
        chatStore = built.store
        revision += 1
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
                    default: break
                    }
                }
            },
            Task { [weak self] in
                // `.revoked` also reaches the client's 401 path, which calls `deviceWasRemoved`.
                for await state in c.states {
                    await self?.setConnectionState(state)
                    switch state {
                    case .revoked: await self?.stopLive()
                    case .connected: await self?.didConnect()
                    default: break
                    }
                }
            },
        ]
    }

    private func setConnectionState(_ state: ConnectionState) { connectionState = state }

    private func startPathMonitor() {
        let monitor = PathMonitor()
        monitor.onChange = { [weak self] network, meaningful in
            guard let self else { return }
            if self.network != network { self.network = network }
            if meaningful { self.liveCalls?.pathChanged() }
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
        await syncInbox()
        await messaging?.reconciler.connected()
    }

    /// A `device.revoked` event (unsigned). For another device it only runs reconcile; one naming this device is
    /// ignored. Only a 401 or 4003 (revoked session) signs this device out and wipes it, through `onSignedOut`.
    private func deviceRevoked(_ id: String) async {
        await refreshDevices()
        await messaging?.reconciler.deviceRevoked(id)
    }

    func callStatus(_ callID: String) async -> CallStatus? {
        guard let signaling = liveCalls?.signaling else { return nil }
        return try? await signaling.status(callID: callID)
    }

    private func stopLive(force: Bool = false) async {
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
