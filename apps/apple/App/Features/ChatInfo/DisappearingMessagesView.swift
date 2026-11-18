import AppCore
import SwiftUI

extension AppModel {
    /// The chat's timer in seconds (0 = off).
    func timerSeconds(for chatID: String) -> Int { _ = revision; return chatStore.timerSeconds(chatID) }

    /// Sets the timer; the store writes the system line and tells the other side.
    func setTimer(_ choice: TimerChoice, chatID: String) { chatStore.setTimer(chatID, seconds: choice.seconds) }
}

extension TimerChoice {
    /// "Off", "1 day", "7 days", "90 days": each its own catalog string so a language words its own plural.
    var title: LocalizedStringKey {
        switch self {
        case .off: "Off"
        case .day: "1 day"
        case .week: "7 days"
        case .quarter: "90 days"
        }
    }
}

/// The chat info row: "Disappearing messages" with the current choice, opening the picker.
struct DisappearingMessagesRow: View {
    let chatID: String
    let model: AppModel

    var body: some View {
        let current = TimerChoice.from(seconds: model.timerSeconds(for: chatID)) ?? .off
        NavigationLink {
            DisappearingMessagesView(chatID: chatID, model: model)
        } label: {
            LabeledContent {
                Text(current.title)
            } label: {
                Label("Disappearing messages", systemImage: "timer")
            }
        }
        .accessibilityIdentifier("chatinfo.timer")
    }
}

/// Off, 1 day, 7 days, 90 days, and what the timer does and does not do.
struct DisappearingMessagesView: View {
    let chatID: String
    let model: AppModel

    var body: some View {
        let current = TimerChoice.from(seconds: model.timerSeconds(for: chatID)) ?? .off
        List {
            Section {
                ForEach(TimerChoice.allCases, id: \.seconds) { choice in
                    Button {
                        model.setTimer(choice, chatID: chatID)
                    } label: {
                        HStack {
                            Text(choice.title)
                            Spacer()
                            if choice == current { Image(systemName: "checkmark").foregroundStyle(.tint) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(choice == current ? .isSelected : [])
                    .accessibilityIdentifier("chatinfo.timer.\(choice.seconds)")
                }
            } footer: {
                Text("New messages disappear from this chat after the time you choose. People can still copy or photograph them.")
            }
        }
        .navigationTitle("Disappearing messages")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}
