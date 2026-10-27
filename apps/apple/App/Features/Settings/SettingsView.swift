import AppCore
import SwiftUI

#if os(iOS)
/// The Settings tab.
struct SettingsView: View {
    @Bindable var model: AppModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink { ProfileView(model: model) } label: {
                        HStack(spacing: 12) {
                            Avatar(name: model.me.displayName, size: 56)
                            VStack(alignment: .leading) {
                                Text(model.me.displayName).font(.title3.weight(.semibold))
                                Text("@\(model.me.username)").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section {
                    NavigationLink { ServerInfoView(model: model) } label: {
                        row("Server", "house.fill", .indigo) {
                            Label { Text(model.server.host) } icon: { Circle().fill(.green).frame(width: 7, height: 7) }
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("settings.server")
                    NavigationLink { LinkedDevicesView(model: model) } label: {
                        row("Linked devices", "laptopcomputer.and.iphone", .gray, value: "\(model.linkedDevices.count)")
                    }
                    .accessibilityIdentifier("settings.devices")
                }
                Section {
                    NavigationLink { PrivacyView(model: model) } label: { row("Privacy", "lock.fill", .blue) }
                        .accessibilityIdentifier("settings.privacy")
                    NavigationLink { DataStorageView(model: model) } label: {
                        row("Data & Storage", "circle.lefthalf.filled", .green, value: model.stripLabel)
                    }
                    .accessibilityIdentifier("settings.data")
                    NavigationLink { NotificationsView() } label: { row("Notifications", "bell.badge.fill", .red) }
                    NavigationLink { ChatFoldersView(model: model) } label: { row("Chat folders", "folder.fill", .orange) }
                    NavigationLink { CallsAudioView(model: model) } label: { row("Calls & Audio", "waveform", .teal) }
                    NavigationLink { AppearanceView() } label: { row("Appearance", "paintbrush.fill", .purple) }
                }
                Section {
                    NavigationLink { HelpView() } label: { row("Help", "questionmark.circle.fill", .gray) }
                }
            }
            .navigationTitle("Settings")
        }
    }

    private func row(_ title: LocalizedStringKey, _ symbol: String, _ color: Color, value: String? = nil) -> some View {
        row(title, symbol, color) {
            if let value { Text(value).foregroundStyle(.secondary) }
        }
    }

    private func row<Trailing: View>(_ title: LocalizedStringKey, _ symbol: String, _ color: Color,
                                     @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.subheadline)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(RoundedRectangle(cornerRadius: 7).fill(color))
                .accessibilityHidden(true)
            Text(title)
            Spacer()
            trailing()
        }
    }
}
#endif

/// Settings window on the Mac.
struct MacSettingsView: View {
    @Bindable var model: AppModel

    var body: some View {
        TabView {
            DataStorageView(model: model).tabItem { Label("Data & Storage", systemImage: "circle.lefthalf.filled") }
            PrivacyView(model: model).tabItem { Label("Privacy", systemImage: "lock") }
            CallsAudioView(model: model).tabItem { Label("Calls & Audio", systemImage: "waveform") }
            ChatFoldersView(model: model).tabItem { Label("Chat folders", systemImage: "folder") }
            LinkedDevicesView(model: model).tabItem { Label("Devices", systemImage: "laptopcomputer.and.iphone") }
            ProfileView(model: model).tabItem { Label("Account", systemImage: "person.crop.circle") }
        }
        .frame(width: 560, height: 520)
    }
}

/// Name, username, and account deletion (App Store Guideline 5.1.1(v)).
struct ProfileView: View {
    @Bindable var model: AppModel
    @State private var confirmDelete = false

    var body: some View {
        Form {
            Section {
                LabeledContent("Name", value: model.me.displayName)
                LabeledContent("Username", value: "@\(model.me.username)")
            }
            Section {
                Button("Delete account", role: .destructive) { confirmDelete = true }
                    .accessibilityIdentifier("profile.delete")
            } footer: {
                Text("Deletes your account and your messages on this server. This can't be undone.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Account")
        .confirmationDialog("Delete your account?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete account", role: .destructive) { model.deleteAccount() }
        }
    }
}

struct ServerInfoView: View {
    let model: AppModel

    var body: some View {
        Form {
            Section {
                LabeledContent("Address", value: model.server.host)
                LabeledContent("Status") {
                    Label { Text("Connected") } icon: { Circle().fill(.green).frame(width: 8, height: 8) }
                }
            } footer: {
                Text("Switching to another server doesn't move your contacts or chats.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Server")
    }
}

struct NotificationsView: View {
    @AppStorage("notify.messages") private var messages = true
    @AppStorage("notify.calls") private var calls = true
    @AppStorage("notify.preview") private var preview = true

    var body: some View {
        Form {
            Toggle("Message notifications", isOn: $messages)
            Toggle("Ring for calls", isOn: $calls)
            Section {
                Toggle("Show message text", isOn: $preview)
            } footer: {
                Text("When off, notifications only say \"New message\".")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Notifications")
    }
}

enum AppearanceChoice: String, CaseIterable {
    case system, light, dark

    var title: LocalizedStringKey {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct AppearanceView: View {
    @AppStorage("appearance") private var appearance = AppearanceChoice.system.rawValue

    var body: some View {
        Form {
            Picker("Appearance", selection: $appearance) {
                ForEach(AppearanceChoice.allCases, id: \.rawValue) { Text($0.title).tag($0.rawValue) }
            }
            .pickerStyle(.inline)
        }
        .formStyle(.grouped)
        .navigationTitle("Appearance")
    }
}

struct HelpView: View {
    var body: some View {
        Form {
            Section("Calls use too much data?") {
                Text("Settings → Data & Storage → Limit on mobile data. You can also tap Data during a call.")
            }
            Section("Can't hear the other person clearly?") {
                Text("Turn on Voice Isolation in Settings → Calls & Audio.")
            }
            Section("Lost your phone?") {
                Text("Sign in again with your passkey or your email. Old messages come back only from a backup.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Help")
    }
}
