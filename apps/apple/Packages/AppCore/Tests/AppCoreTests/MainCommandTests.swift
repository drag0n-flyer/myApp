import Testing
@testable import AppCore

private func enabled(_ c: MainCommand, locked: Bool = false, signedIn: Bool = true, open: Bool = true, lockOn: Bool = true) -> Bool {
    c.isEnabled(locked: locked, signedIn: signedIn, hasOpenChat: open, lockOn: lockOn)
}

@Test func commandsThatShowOrChangeChatContentAreDisabledWhileLocked() {
    for c in MainCommand.allCases {
        #expect(!enabled(c, locked: true), "\(c) must be off while locked")
    }
}

@Test func commandsAreDisabledWhenSignedOut() {
    for c in MainCommand.allCases {
        #expect(!enabled(c, signedIn: false), "\(c) must be off when signed out")
    }
}

@Test func lockNowNeedsTheLockOnAndTheAppUnlocked() {
    #expect(enabled(.lockNow))
    #expect(!enabled(.lockNow, lockOn: false))
    #expect(!enabled(.lockNow, locked: true))
    #expect(enabled(.lockNow, open: false))
}

@Test func chatCommandsNeedAnOpenChat() {
    for c in [MainCommand.chatInfo, .toggleMute, .archive] {
        #expect(enabled(c))
        #expect(!enabled(c, open: false))
    }
    for c in [MainCommand.newChat, .search, .nextChat, .previousChat] {
        #expect(enabled(c, open: false))
    }
}

@Test func lockOffDoesNotDisableOtherCommands() {
    #expect(enabled(.newChat, lockOn: false))
    #expect(enabled(.archive, lockOn: false))
}

@Test func shortcutsMatchTheSpec() {
    #expect(MainCommand.newChat.shortcut == MainCommandShortcut(key: "n", modifiers: [.command]))
    #expect(MainCommand.search.shortcut == MainCommandShortcut(key: "f", modifiers: [.command]))
    #expect(MainCommand.nextChat.shortcut == MainCommandShortcut(key: "\t", modifiers: [.control]))
    #expect(MainCommand.previousChat.shortcut == MainCommandShortcut(key: "\t", modifiers: [.control, .shift]))
    #expect(MainCommand.chatInfo.shortcut == MainCommandShortcut(key: "i", modifiers: [.command]))
    #expect(MainCommand.toggleMute.shortcut == MainCommandShortcut(key: "m", modifiers: [.command, .shift]))
    #expect(MainCommand.archive.shortcut == MainCommandShortcut(key: "a", modifiers: [.control, .command]))
    #expect(MainCommand.lockNow.shortcut == MainCommandShortcut(key: "l", modifiers: [.control, .command]))
}

@Test func everyShortcutIsDistinct() {
    let all = MainCommand.allCases.map(\.shortcut)
    #expect(Set(all).count == all.count)
}

@Test func chatCycleMovesAndWraps() {
    let ids = ["a", "b", "c"]
    #expect(ChatCycle.adjacent(in: ids, current: "a", step: 1) == "b")
    #expect(ChatCycle.adjacent(in: ids, current: "c", step: 1) == "a")
    #expect(ChatCycle.adjacent(in: ids, current: "a", step: -1) == "c")
    #expect(ChatCycle.adjacent(in: ids, current: "b", step: -1) == "a")
}

@Test func chatCycleWithNoCurrentChatStartsAtTheEnds() {
    let ids = ["a", "b", "c"]
    #expect(ChatCycle.adjacent(in: ids, current: nil, step: 1) == "a")
    #expect(ChatCycle.adjacent(in: ids, current: nil, step: -1) == "c")
    #expect(ChatCycle.adjacent(in: ids, current: "gone", step: 1) == "a")
    #expect(ChatCycle.adjacent(in: [], current: "a", step: 1) == nil)
    #expect(ChatCycle.adjacent(in: ["a"], current: "a", step: 1) == "a")
}
