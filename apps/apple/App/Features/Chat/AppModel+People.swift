import AppCore
import Foundation
import LocalStore
import Messaging
import SwiftUI

/// The signal sender `PeopleSignals` was built with; sample mode has none.
@MainActor
final class SwitchableSignalSender: SignalSending {
    var target: (any SignalSending)?
    func send(chatID: String, body: ContentEnvelope.Body) async { await target?.send(chatID: chatID, body: body) }
}

/// Typing and presence (spec 6.1). What is sent when, and what a chat shows, is decided in `PeopleSignals` (Messaging
/// package, tested); this file only wires it to the app.
extension AppModel {
    /// Builds (or rebuilds) `people`. Live: sends and receives through `channel`, keeps "last witnessed" in `repo`'s
    /// key-value store. Sample mode and signed-out: nothing is sent, and `scripted` plays a fixed typing scene.
    func makePeople(repo: Repository?, channel: EphemeralChannel?, scripted: Bool = false) {
        peopleTasks.forEach { $0.cancel() }
        peopleTasks = []
        let sender = SwitchableSignalSender()
        if let channel { sender.target = EphemeralOutbox(channel: channel) }
        let now: @MainActor () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
        let typing: @MainActor () -> Bool = { [weak self] in self?.settings.typingIndicator ?? false }
        let presence: @MainActor () -> Bool = { [weak self] in self?.settings.onlineAndLastSeen ?? false }
        let blocked: @MainActor (String) -> Bool = { [weak self] chatID in
            guard let self else { return true }
            return self.chatStore.blockedIDs.contains(self.chatStore.chat(id: chatID)?.contactID ?? chatID)
        }
        let targets: @MainActor () -> [String] = { [weak self] in
            self?.chatStore.chats(in: .all).filter { !$0.isRequest }.map(\.id) ?? []
        }
        let made: PeopleSignals
        if let repo {
            made = PeopleSignals(repo: repo, sender: sender, now: now, typingEnabled: typing, presenceEnabled: presence,
                                 isBlocked: blocked, presenceTargets: targets)
        } else {
            var seen: [String: Int64] = [:]
            made = PeopleSignals(sender: sender, now: now, typingEnabled: typing, presenceEnabled: presence,
                                 isBlocked: blocked, presenceTargets: targets,
                                 loadSeen: { seen[$0] }, saveSeen: { seen[$0] = $1 })
        }
        made.onChange = { [weak self] in self?.revision += 1 }
        people = made
        if peopleActive { made.foreground() }

        if let channel {
            peopleTasks.append(Task { [weak self] in
                for await signal in channel.events { self?.people?.ingest(signal) }
            })
        }
        peopleTasks.append(Task { [weak self] in
            var lastRevision = -1
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, let people = self.people else { return }
                if self.revision != lastRevision {
                    lastRevision = self.revision
                    self.observeIncomingMessages(people)
                }
                people.tick()
            }
        })
        if scripted { startScriptedTyping() }
    }

    private func observeIncomingMessages(_ people: PeopleSignals) {
        let me = chatStore.me.id
        for chat in chatStore.chats(in: .all) where !chat.isRequest {
            if let last = chat.messages.last(where: { $0.senderID != me }) { people.incomingMessage(chatID: chat.id, id: last.id) }
        }
    }

    /// The app came to the foreground or went to the background.
    func peopleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            peopleActive = true
            people?.foreground()
        case .background:
            peopleActive = false
            people?.background()
        default: break
        }
    }

    /// The connection is up: tell the open chats at once.
    func peopleConnected() {
        if peopleActive { people?.foreground() }
    }

    // MARK: What the screens show

    /// Nothing about a chat shows while the app-lock cover is up.
    private var peopleHidden: Bool { lock.coversContent }

    /// "typing…" in the chat list row and the chat header.
    func isTyping(chatID: String) -> Bool {
        _ = revision
        return !peopleHidden && people?.isTyping(chatID: chatID) == true
    }

    /// The line under the name in the chat header: "typing…", "online", "last seen 9:38", or nothing.
    func headerStatus(chatID: String) -> String? {
        _ = revision
        guard !peopleHidden, let people, chatStore.chat(id: chatID)?.contactID != chatStore.me.id else { return nil }
        if people.isTyping(chatID: chatID) { return String(localized: "typing…") }
        switch people.presence(chatID: chatID) {
        case .online: return String(localized: "online")
        case .lastSeen(let ms):
            let when = TimeLabel.format(Date(timeIntervalSince1970: Double(ms) / 1000))
            return String(localized: "last seen \(when)")
        case .none: return nil
        }
    }

    // MARK: Composer

    func composerChanged(chatID: String, isEmpty: Bool) { people?.composerChanged(chatID: chatID, isEmpty: isEmpty) }
    func composerSent(chatID: String) { people?.composerSent(chatID: chatID) }

    // MARK: Sample mode

    /// UI tests (`-scriptedTyping`): Riya shows as online and typing in the sample chat for about ten seconds, then her
    /// message arrives and the typing line goes away.
    private func startScriptedTyping() {
        peopleTasks.append(Task { [weak self] in
            let chatID = "chat-riya"
            try? await Task.sleep(for: .milliseconds(500))
            var waited = 0
            while waited < 10_000, !Task.isCancelled {
                guard let self else { return }
                self.people?.ingest(EphemeralSignal(chatID: chatID, accountID: "riya", body: .presence(.online)))
                self.people?.ingest(EphemeralSignal(chatID: chatID, accountID: "riya", body: .typing(on: true)))
                try? await Task.sleep(for: .seconds(3))
                waited += 3_000
            }
            guard let self, let riya = self.chatStore.contact(id: "riya") else { return }
            self.chatStore.receive(.text("On my way!"), from: riya)
        })
    }
}
