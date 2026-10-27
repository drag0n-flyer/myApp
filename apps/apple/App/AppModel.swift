import AppCore
import Foundation
import Observation

enum AppTab: Hashable {
    case contacts, calls, chats, settings, search
}

/// Root state for the app. Wraps the sample services; real services replace them later
/// behind the same AppCore protocols.
@MainActor
@Observable
final class AppModel {
    let chatStore: SampleChatStore
    let calls: SimulatedCallEngine
    let account: SampleAccountService

    var server: ServerPolicy {
        didSet { calls.server = server }
    }
    var settings: UserSettings {
        didSet { calls.settings = settings.call }
    }
    var network: NetworkType {
        didSet { calls.setNetwork(network) }
    }
    var onboarding: OnboardingFlow
    var selectedTab: AppTab = .chats
    var selectedFolder: ChatFolder = .all
    var searchText = ""
    /// Chat opened from outside the chat list (Mac sidebar, Contacts, Calls).
    var openChatID: String?
    private(set) var activeCall: CallState?
    var pendingInvite: InviteLink?
    /// Mac only: a contact who is ringing (macOS has no CallKit).
    var incomingCaller: Contact?
    let isUITesting: Bool

    /// Bumped after every store change so views that read store data refresh.
    private(set) var revision = 0

    init(launch: LaunchOptions = .current) {
        let account = SampleAccountService()
        let server = account.defaultServer
        let settings = UserSettings.initial(from: server)
        self.account = account
        self.server = server
        self.settings = settings
        self.network = launch.network
        self.isUITesting = launch.isUITesting
        self.chatStore = SampleChatStore()
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
        self.onboarding = flow
        if let url = launch.openURL { handle(url: url) }
        calls.onChange = { [weak self] state in
            self?.activeCall = state
        }
    }

    // MARK: - Chats (every read touches `revision` so SwiftUI tracks store changes)

    var folders: [ChatFolder] { _ = revision; return chatStore.folders }

    func chats(in folder: ChatFolder) -> [Chat] {
        _ = revision
        let chats = chatStore.chats(in: folder)
        let query = searchText.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return chats }
        return chats.filter { chat in
            let name = chatStore.contact(id: chat.contactID)?.displayName ?? ""
            return name.localizedCaseInsensitiveContains(query)
                || chat.messages.contains { $0.previewText.localizedCaseInsensitiveContains(query) }
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

    @discardableResult
    func addContact(username: String) -> Contact? {
        defer { revision += 1 }
        return chatStore.addContact(username: username)
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
    var linkedDevices: [LinkedDevice] = LinkedDevice.samples

    func linkSampleDevice() {
        linkedDevices.append(LinkedDevice(id: UUID().uuidString, name: String(localized: "New Mac"), symbol: "laptopcomputer",
                                          lastActive: String(localized: "Just now"), isThisDevice: false))
    }

    func removeDevice(id: String) { linkedDevices.removeAll { $0.id == id && !$0.isThisDevice } }

    /// Sample build: forgets the account and returns to the welcome screen.
    func deleteAccount() {
        #if os(macOS)
        onboarding = OnboardingFlow(platform: .mac)
        #else
        onboarding = OnboardingFlow(platform: .iPhone)
        #endif
        selectedTab = .chats
    }

    // MARK: - Calls

    var stripLabel: String { DataModePolicy.stripLabel(server: server, settings: settings.call, network: network) }

    func startCall(contactID: String, video: Bool) {
        guard let contact = chatStore.contact(id: contactID) else { return }
        calls.start(with: contact, video: video)
    }

    func dismissEndedCall() {
        if activeCall?.isEnded == true { activeCall = nil }
    }

    // MARK: - Links

    func handle(url: URL) {
        guard let invite = InviteLink.parse(url) else { return }
        pendingInvite = invite
    }
}

/// Launch arguments used by UI tests: `-skipOnboarding`, `-uiTesting`, `-network mobile|wifi`, `-openURL <url>`.
struct LaunchOptions {
    var skipOnboarding = false
    /// Skips system permission prompts so UI tests aren't blocked by alerts.
    var isUITesting = false
    var network: NetworkType = .mobile
    var openURL: URL?

    static var current: LaunchOptions {
        let args = ProcessInfo.processInfo.arguments
        var options = LaunchOptions()
        options.skipOnboarding = args.contains("-skipOnboarding")
        options.isUITesting = args.contains("-uiTesting")
        if let i = args.firstIndex(of: "-network"), i + 1 < args.count {
            options.network = args[i + 1] == "wifi" ? .wifi : .mobile
        }
        if let i = args.firstIndex(of: "-openURL"), i + 1 < args.count {
            options.openURL = URL(string: args[i + 1])
        }
        return options
    }
}
