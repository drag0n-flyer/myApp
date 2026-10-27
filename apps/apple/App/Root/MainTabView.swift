import SwiftUI

#if os(iOS)
/// iOS 26+: floating glass tab bar with a separate search tab.
/// iOS 17–25: standard tab bar; search lives in the Chats and Contacts lists.
struct MainTabView: View {
    @Bindable var model: AppModel

    var body: some View {
        if #available(iOS 26, *) {
            floatingTabs
        } else {
            standardTabs
        }
    }

    @available(iOS 26, *)
    private var floatingTabs: some View {
        TabView(selection: $model.selectedTab) {
            Tab("Contacts", systemImage: "person.2", value: AppTab.contacts) {
                ContactsView(model: model, showsSearchField: false)
            }
            Tab("Calls", systemImage: "phone", value: AppTab.calls) {
                CallHistoryView(model: model)
            }
            Tab("Chats", systemImage: "bubble.left.and.bubble.right", value: AppTab.chats) {
                ChatListView(model: model, showsSearchField: false)
            }
            Tab("Settings", systemImage: "gear", value: AppTab.settings) {
                SettingsView(model: model)
            }
            Tab(value: AppTab.search, role: .search) {
                SearchView(model: model)
            }
        }
    }

    private var standardTabs: some View {
        TabView(selection: $model.selectedTab) {
            ContactsView(model: model, showsSearchField: true)
                .tabItem { Label("Contacts", systemImage: "person.2") }
                .tag(AppTab.contacts)
            CallHistoryView(model: model)
                .tabItem { Label("Calls", systemImage: "phone") }
                .tag(AppTab.calls)
            ChatListView(model: model, showsSearchField: true)
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
                .tag(AppTab.chats)
            SettingsView(model: model)
                .tabItem { Label("Settings", systemImage: "gear") }
                .tag(AppTab.settings)
        }
    }
}
#endif
