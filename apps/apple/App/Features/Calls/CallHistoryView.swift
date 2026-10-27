import AppCore
import SwiftUI

struct CallRecord: Identifiable {
    let id: String
    let contactID: String
    let name: String
    let summary: CallSummary
    let date: Date
    let isOutgoing: Bool

    var isMissed: Bool {
        switch summary {
        case .missedAudio, .missedVideo: true
        default: false
        }
    }

    var isVideo: Bool {
        switch summary {
        case .missedVideo, .video: true
        default: false
        }
    }
}

extension AppModel {
    /// Calls recorded in chats, newest first.
    var callHistory: [CallRecord] {
        (chats(in: .all) + messageRequests)
            .flatMap { chat in
                chat.messages.compactMap { message -> CallRecord? in
                    guard case .call(let summary) = message.content else { return nil }
                    return CallRecord(id: message.id, contactID: chat.contactID,
                                      name: contact(id: chat.contactID)?.displayName ?? "",
                                      summary: summary, date: message.sentAt,
                                      isOutgoing: message.senderID == chatStore.me.id)
                }
            }
            .sorted { $0.date > $1.date }
    }
}

/// The Calls tab.
struct CallHistoryView: View {
    @Bindable var model: AppModel
    @State private var missedOnly = false

    var body: some View {
        NavigationStack {
            CallHistoryContent(model: model, missedOnly: $missedOnly)
                .navigationTitle("Calls")
        }
    }
}

/// Shared by the Calls tab and the Mac sidebar.
struct CallHistoryContent: View {
    @Bindable var model: AppModel
    @Binding var missedOnly: Bool

    var body: some View {
        List {
            HStack(spacing: 7) {
                chip("All", selected: !missedOnly, id: "calls.chip.all") { missedOnly = false }
                chip("Missed", selected: missedOnly, id: "calls.chip.missed") { missedOnly = true }
            }
            .listRowSeparator(.hidden)
            let records = model.callHistory.filter { !missedOnly || $0.isMissed }
            if records.isEmpty {
                ContentUnavailableView("No calls", systemImage: "phone")
            }
            ForEach(records) { record in
                HStack(spacing: 11) {
                    Avatar(name: record.name, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(record.name).font(.headline).foregroundStyle(record.isMissed ? Color.red : Color.primary).lineLimit(1)
                        Label(directionText(record), systemImage: record.isVideo ? "video.fill" : "phone.fill")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(TimeLabel.format(record.date)).font(.caption).foregroundStyle(.secondary)
                    Button {
                        model.startCall(contactID: record.contactID, video: record.isVideo)
                    } label: {
                        Image(systemName: record.isVideo ? "video" : "phone")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text("Call \(record.name)"))
                    .accessibilityIdentifier("calls.callback.\(record.id)")
                }
                .accessibilityIdentifier("calls.row.\(record.id)")
            }
        }
        .listStyle(.plain)
    }

    private func directionText(_ record: CallRecord) -> String {
        if record.isMissed { return String(localized: "Missed") }
        return record.isOutgoing ? String(localized: "Outgoing") : String(localized: "Incoming")
    }

    private func chip(_ title: LocalizedStringKey, selected: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .foregroundStyle(selected ? Color.white : Color.primary)
                .background(Capsule().fill(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary)))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}
