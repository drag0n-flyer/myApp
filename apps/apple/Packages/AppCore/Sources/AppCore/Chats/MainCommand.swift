import Foundation

/// A modifier key of a menu shortcut (the app maps these to SwiftUI's).
public enum MainCommandModifier: Hashable, Sendable {
    case command, control, shift
}

/// A menu shortcut as plain data: `key` is a character ("\t" is Tab).
public struct MainCommandShortcut: Hashable, Sendable {
    public let key: Character
    public let modifiers: Set<MainCommandModifier>

    public init(key: Character, modifiers: Set<MainCommandModifier>) {
        self.key = key
        self.modifiers = modifiers
    }
}

/// The Mac menu commands of the main window (Settings, ⌘, is the system's own).
///
/// One rule decides whether each is usable. Every command that shows or changes chat content is off while the app is
/// locked or signed out, so a shortcut pressed behind the lock cover does nothing.
public enum MainCommand: CaseIterable, Sendable {
    case newChat, search, nextChat, previousChat, chatInfo, toggleMute, archive, lockNow

    public func isEnabled(locked: Bool, signedIn: Bool, hasOpenChat: Bool, lockOn: Bool) -> Bool {
        guard signedIn, !locked else { return false }
        switch self {
        case .newChat, .search, .nextChat, .previousChat: return true
        case .chatInfo, .toggleMute, .archive: return hasOpenChat
        case .lockNow: return lockOn
        }
    }

    public var shortcut: MainCommandShortcut {
        switch self {
        case .newChat: MainCommandShortcut(key: "n", modifiers: [.command])
        case .search: MainCommandShortcut(key: "f", modifiers: [.command])
        case .nextChat: MainCommandShortcut(key: "\t", modifiers: [.control])
        case .previousChat: MainCommandShortcut(key: "\t", modifiers: [.control, .shift])
        case .chatInfo: MainCommandShortcut(key: "i", modifiers: [.command])
        case .toggleMute: MainCommandShortcut(key: "m", modifiers: [.command, .shift])
        case .archive: MainCommandShortcut(key: "a", modifiers: [.control, .command])
        case .lockNow: MainCommandShortcut(key: "l", modifiers: [.control, .command])
        }
    }
}

/// Next Chat / Previous Chat: which chat of the visible list follows.
public enum ChatCycle {
    /// The chat `step` places from `current`, wrapping at the ends. With no (or an unknown) current chat, a forward
    /// step gives the first and a backward step the last. Nil for an empty list.
    public static func adjacent(in ids: [String], current: String?, step: Int) -> String? {
        guard !ids.isEmpty else { return nil }
        guard let current, let index = ids.firstIndex(of: current) else {
            return step >= 0 ? ids.first : ids.last
        }
        let count = ids.count
        let next = ((index + step) % count + count) % count
        return ids[next]
    }
}
