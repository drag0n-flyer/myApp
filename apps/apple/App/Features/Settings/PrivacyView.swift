import AppCore
import SwiftUI

struct PrivacyView: View {
    @Bindable var model: AppModel

    var body: some View {
        Form {
            Section("Hide my IP address in calls") {
                ForEach(IPHiding.allCases, id: \.self) { option in
                    Button { model.settings.ipHiding = option } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(option.title).foregroundStyle(.primary)
                                Text(option.subtitle).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.settings.ipHiding == option {
                                Image(systemName: "checkmark").foregroundStyle(.tint).fontWeight(.semibold)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(model.settings.ipHiding == option ? .isSelected : [])
                    .accessibilityIdentifier("privacy.ip.\(option)")
                }
            }

            Section("Who can find me") {
                Toggle("By username", isOn: $model.settings.findByUsername)
                Toggle("By email", isOn: $model.settings.findByEmail)
                    .accessibilityIdentifier("privacy.findemail")
            }

            Section("Messages") {
                Toggle("Read receipts", isOn: $model.settings.readReceipts)
                Picker("Disappearing messages", selection: $model.settings.disappearingMessages) {
                    Text("Off").tag(Double?.none)
                    Text("1 day").tag(Double?.some(86_400))
                    Text("1 week").tag(Double?.some(604_800))
                    Text("4 weeks").tag(Double?.some(2_419_200))
                }
                NavigationLink {
                    BlockedListView(model: model)
                } label: {
                    LabeledContent("Blocked", value: "\(model.chatStore.blockedIDs.count)")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Privacy")
    }
}

private struct BlockedListView: View {
    let model: AppModel

    var body: some View {
        List(Array(model.chatStore.blockedIDs).sorted(), id: \.self) { id in
            Text(id)
        }
        .overlay {
            if model.chatStore.blockedIDs.isEmpty {
                ContentUnavailableView("No one blocked", systemImage: "hand.raised")
            }
        }
        .navigationTitle("Blocked")
    }
}
