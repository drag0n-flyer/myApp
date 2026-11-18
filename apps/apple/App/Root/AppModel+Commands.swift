import AppCore
import Foundation

/// What the Mac menu commands do. `MainCommand.isEnabled` (AppCore, tested) decides whether a command may run; every
/// action checks it again, so a shortcut that reaches here while the app is locked or signed out does nothing.
extension AppModel {
    var commandSignedIn: Bool { onboarding.step == .done && !hasBrokenSetup }

    /// The chat on screen, if it still exists.
    private var commandChat: Chat? { onScreenChatID.flatMap { chat(id: $0) } }

    func isEnabled(_ command: MainCommand) -> Bool {
        command.isEnabled(locked: lock.coversContent, signedIn: commandSignedIn,
                          hasOpenChat: commandChat != nil, lockOn: lock.appLockOn)
    }

    /// Mute or Unmute for the menu. Says nothing about the chat while the app is locked.
    var muteCommandUnmutes: Bool { !lock.coversContent && (commandChat?.muted ?? false) }

    func perform(_ command: MainCommand) {
        guard isEnabled(command) else { return }
        switch command {
        case .newChat:
            newChatRequests += 1
        case .search:
            if onScreenChatID != nil { requestSearchInOpenChat() } else { listSearchRequests += 1 }
        case .nextChat:
            openAdjacentChat(step: 1)
        case .previousChat:
            openAdjacentChat(step: -1)
        case .chatInfo:
            chatInfoRequests += 1
        case .toggleMute:
            if let chat = commandChat { setMuted(chat.id, !chat.muted) }
        case .archive:
            if let chat = commandChat { setArchived(chat.id, true) }
        case .lockNow:
            lock.lockNow()
        }
    }

    /// Search in the chat that is open (⌘F). The in-chat search task replaces the body with its own request; until
    /// then it asks the chat screen to open its search field the way chat info's Search button does.
    func requestSearchInOpenChat() {
        guard let id = onScreenChatID else { return }
        chatSearchRequest = id
    }

    private func openAdjacentChat(step: Int) {
        let ids = chats(in: selectedFolder, matching: chatListQuery).map(\.id)
        if let next = ChatCycle.adjacent(in: ids, current: onScreenChatID, step: step) { openChatID = next }
    }
}
