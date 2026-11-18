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
            if let chat = commandChat {
                let ids = chats(in: selectedFolder, matching: chatListQuery).map(\.id)
                let next = ChatCycle.openAfterArchiving(in: ids, archiving: chat.id)
                setArchived(chat.id, true)
                if let next { openChatID = next } else { closeChatRequests += 1 }
            }
        case .lockNow:
            lock.lockNow()
        }
    }

    /// Search in the chat that is open (⌘F): the chat screen opens its search bar.
    func requestSearchInOpenChat() {
        openSearchInOpenChat()
    }

    private func openAdjacentChat(step: Int) {
        let ids = chats(in: selectedFolder, matching: chatListQuery).map(\.id)
        if let next = ChatCycle.adjacent(in: ids, current: onScreenChatID, step: step) { openChatID = next }
    }
}
