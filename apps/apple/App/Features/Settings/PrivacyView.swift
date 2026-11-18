import AppCore
import SwiftUI

struct PrivacyView: View {
    @Bindable var model: AppModel
    @State private var findByEmailError: AccountError?

    private static func delayTitle(_ seconds: Int) -> LocalizedStringKey {
        switch seconds {
        case 0: "Immediately"
        case 300: "5 minutes"
        case 3600: "1 hour"
        default: "1 minute"
        }
    }

    var body: some View {
        let _ = model.revision
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
                Toggle("By email", isOn: Binding(
                    get: { model.settings.findByEmail },
                    set: { on in Task { findByEmailError = await model.setFindByEmail(on) } }))
                    .accessibilityIdentifier("privacy.findemail")
            }

            Section {
                let available = model.lock.availability == .ready
                Toggle(isOn: Binding(
                    get: { model.lock.appLockOn },
                    set: { on in Task { _ = await model.setAppLock(on) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("App lock")
                        Group {
                            if available || model.lock.appLockOn {
                                Text("Ask for your fingerprint, face or screen lock to open \(AppBrand.name)")
                            } else {
                                Text("Set a screen lock in your phone's settings first.")
                            }
                        }
                        .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .disabled(!available && !model.lock.appLockOn)
                .accessibilityLabel("App lock")
                .accessibilityIdentifier("privacy.applock")
                if model.lock.appLockOn {
                    Picker("Lock after", selection: Binding(
                        get: { model.lock.lockAfterSeconds },
                        set: { model.setLockAfter($0) })) {
                        ForEach(AppLockPolicy.delays, id: \.self) { seconds in
                            Text(Self.delayTitle(seconds)).tag(seconds)
                        }
                    }
                    .accessibilityIdentifier("privacy.lockafter")
                }
            }

            Section("Messages") {
                Toggle("Read receipts", isOn: $model.settings.readReceipts)
                NavigationLink {
                    BlockedListView(model: model)
                } label: {
                    LabeledContent("Blocked", value: "\(model.chatStore.blockedIDs.count)")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Privacy")
        .task { await model.loadFindByEmail() }
        .alert("Couldn't save", isPresented: Binding(
            get: { findByEmailError != nil }, set: { if !$0 { findByEmailError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(findByEmailError?.message ?? "")
        }
    }
}

private struct BlockedListView: View {
    let model: AppModel

    /// Name, then username, then the ID (a person never seen before has only the ID).
    private func label(for id: String) -> String {
        if let contact = model.contact(id: id) {
            if !contact.displayName.isEmpty { return contact.displayName }
            if !contact.username.isEmpty { return "@\(contact.username)" }
        }
        return id
    }

    var body: some View {
        let _ = model.revision   // the store is not observable: re-read after every change
        List(Array(model.chatStore.blockedIDs).sorted(), id: \.self) { id in
            HStack {
                Text(label(for: id))
                Spacer()
                Button("Unblock") { model.unblock(contactID: id) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text("Unblock \(label(for: id))"))
                    .accessibilityIdentifier("blocked.unblock.\(id)")
            }
        }
        .overlay {
            if model.chatStore.blockedIDs.isEmpty {
                ContentUnavailableView("No one blocked", systemImage: "hand.raised")
            }
        }
        .navigationTitle("Blocked")
    }
}
