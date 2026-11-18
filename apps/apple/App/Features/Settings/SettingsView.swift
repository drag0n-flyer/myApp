import AppCore
import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

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
                            Label { Text(model.server.host) } icon: {
                                Circle().fill(model.connectionStatus.dotColor).frame(width: 7, height: 7)
                            }
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
                    Button { openNotificationSettings() } label: {
                        row("Notification settings", "bell.badge.fill", .red) {
                            Image(systemName: "arrow.up.forward.app").foregroundStyle(.secondary).accessibilityHidden(true)
                        }
                    }
                    .foregroundStyle(.primary)
                    .accessibilityIdentifier("settings.notifications")
                    NavigationLink { ChatFoldersView(model: model) } label: { row("Chat folders", "folder.fill", .orange) }
                    NavigationLink { CallsAudioView(model: model) } label: { row("Calls & Audio", "waveform", .teal) }
                    NavigationLink { AppearanceView() } label: { row("Appearance", "paintbrush.fill", .purple) }
                }
                Section {
                    NavigationLink { HelpView() } label: { row("Help", "questionmark.circle.fill", .gray) }
                }
                Section {
                    NavigationLink { LicensesView() } label: { row("Open-source licenses", "doc.text.fill", .gray) }
                        .accessibilityIdentifier("settings.licenses")
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
            NotificationSettingsForm().tabItem { Label("Notifications", systemImage: "bell") }
            PrivacyView(model: model).tabItem { Label("Privacy", systemImage: "lock") }
            CallsAudioView(model: model).tabItem { Label("Calls & Audio", systemImage: "waveform") }
            ChatFoldersView(model: model).tabItem { Label("Chat folders", systemImage: "folder") }
            LinkedDevicesView(model: model).tabItem { Label("Devices", systemImage: "laptopcomputer.and.iphone") }
            ProfileView(model: model).tabItem { Label("Account", systemImage: "person.crop.circle") }
            NavigationStack { LicensesView() }.tabItem { Label("Licenses", systemImage: "doc.text") }
        }
        .frame(width: 560, height: 520)
    }
}

/// Name, username, and account deletion (App Store Guideline 5.1.1(v)).
struct ProfileView: View {
    @Bindable var model: AppModel
    @State private var confirmDelete = false
    @State private var deleteError: AccountError?
    @State private var confirmSignOut = false
    @State private var signOutError: SignOutDecision?

    var body: some View {
        Form {
            Section {
                LabeledContent("Name", value: model.me.displayName)
                LabeledContent("Username", value: "@\(model.me.username)")
            }
            Section {
                Button("Sign out", role: .destructive) { confirmSignOut = true }
                    .disabled(model.isSigningOut)
                    .accessibilityIdentifier("profile.signout")
            }
            Section {
                Button("Delete account", role: .destructive) { confirmDelete = true }
                    .accessibilityIdentifier("profile.delete")
            } footer: {
                Text("Deletes your account and your messages on this server. This can't be undone.")
            }
            AccountErrorText(error: deleteError)
        }
        .formStyle(.grouped)
        .navigationTitle("Account")
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) {
                guard !model.isSigningOut else { return }
                Task {
                    let result = await model.signOut()
                    signOutError = result == .wipe ? nil : result
                }
            }
        } message: {
            Text("Sign out of this device? Your messages on this device will be removed.")
        }
        .alert(signOutError == .noConnection ? Text("No connection") : Text("Couldn't sign out"),
               isPresented: Binding(get: { signOutError != nil }, set: { if !$0 { signOutError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            if signOutError == .noConnection {
                Text("Connect to the internet and try again. Nothing was removed from this device.")
            } else {
                Text("Something went wrong on the server. Try again later.")
            }
        }
        .confirmationDialog("Delete your account?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete account", role: .destructive) {
                Task { deleteError = await model.deleteAccount() }
            }
        } message: {
            Text("Delete your account? Your messages on this device are erased and can't be recovered.")
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
                    Label { Text(model.connectionStatus.title) } icon: {
                        Circle().fill(model.connectionStatus.dotColor).frame(width: 8, height: 8)
                    }
                    .accessibilityIdentifier("server.status")
                }
            } footer: {
                Text("Switching to another server doesn't move your contacts or chats.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Server")
    }
}

/// Opens the system settings page for this app's notifications (the app has no switches of its own).
@MainActor
func openNotificationSettings() {
    #if os(iOS)
    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) }
    #else
    if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
        NSWorkspace.shared.open(url)
    }
    #endif
}

/// The Mac settings tab for notifications: one button to System Settings.
struct NotificationSettingsForm: View {
    var body: some View {
        Form {
            Button("Notification settings") { openNotificationSettings() }
                .accessibilityIdentifier("settings.notifications")
        }
        .formStyle(.grouped)
        .navigationTitle("Notifications")
    }
}

extension ConnectionStatus {
    var title: LocalizedStringKey {
        switch self {
        case .connected: "Connected"
        case .connecting: "Connecting…"
        case .offline: "No connection"
        }
    }

    var dotColor: Color { self == .connected ? .green : .gray }
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
                Text("Sign in again with your passkey or your email. A new device starts with empty history unless you link it from a device that still has your messages.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Help")
    }
}
