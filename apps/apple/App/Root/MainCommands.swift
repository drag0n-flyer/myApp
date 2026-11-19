import AppCore
import SwiftUI

#if os(macOS)
extension MainCommandShortcut {
    var keyboardShortcut: KeyboardShortcut {
        var flags: EventModifiers = []
        if modifiers.contains(.command) { flags.insert(.command) }
        if modifiers.contains(.control) { flags.insert(.control) }
        if modifiers.contains(.shift) { flags.insert(.shift) }
        let equivalent: KeyEquivalent = key == "\t" ? .tab : KeyEquivalent(key)
        return KeyboardShortcut(equivalent, modifiers: flags)
    }
}

/// The Mac menu bar's commands. Each item is disabled by `AppModel.isEnabled` (the AppCore rule), so none works while
/// the app is locked or signed out. Settings (⌘,) is the system's own item.
struct MainCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            item(.newChat, title: "New Chat", symbol: "square.and.pencil")
        }
        // Replaces the system Find items: their ⌘F would come first and swallow the key.
        CommandGroup(replacing: .textEditing) {
            item(.search, title: model.onScreenChatID != nil && !model.lock.coversContent ? "Search in chat" : "Search",
                 symbol: "magnifyingglass")
        }
        CommandGroup(after: .appSettings) {
            item(.lockNow, title: "Lock Now", symbol: "lock")
        }
        CommandGroup(before: .toolbar) {
            item(.showChats, title: "Chats", symbol: "bubble.left.and.bubble.right")
            item(.showCalls, title: "Calls", symbol: "phone")
            item(.showContacts, title: "Contacts", symbol: "person.2")
            Divider()
        }
        CommandMenu("Chat") {
            item(.nextChat, title: "Next Chat", symbol: "chevron.down")
            item(.previousChat, title: "Previous Chat", symbol: "chevron.up")
            Divider()
            item(.chatInfo, title: "Chat Info", symbol: "info.circle")
            if model.muteCommandUnmutes {
                item(.toggleMute, title: "Unmute", symbol: "bell")
            } else {
                item(.toggleMute, title: "Mute", symbol: "bell.slash")
            }
            item(.archive, title: "Archive", symbol: "archivebox")
        }
    }

    private func item(_ command: MainCommand, title: LocalizedStringKey, symbol: String) -> some View {
        Button { model.perform(command) } label: { Label(title, systemImage: symbol) }
            .keyboardShortcut(command.shortcut.keyboardShortcut)
            .disabled(!model.isEnabled(command))
            .accessibilityIdentifier("menu.\(String(describing: command))")
    }
}
#endif
