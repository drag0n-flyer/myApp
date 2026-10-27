import SwiftUI

#if os(iOS)
/// Receives the APNs token (SwiftUI has no callback for it).
final class AppDelegate: NSObject, UIApplicationDelegate {
    @MainActor static weak var model: AppModel?

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in await AppDelegate.model?.didReceivePushToken(deviceToken) }
    }
}
#else
final class AppDelegate: NSObject, NSApplicationDelegate {
    @MainActor static weak var model: AppModel?

    func application(_ application: NSApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in await AppDelegate.model?.didReceivePushToken(deviceToken) }
    }
}
#endif

@main
struct AppMain: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    #if os(iOS)
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    #else
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    #endif

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .onOpenURL { model.handle(url: $0) }
                .onAppear { AppDelegate.model = model }
        }
        .onChange(of: scenePhase) { _, phase in model.scenePhaseChanged(phase) }
        #if os(macOS)
        Window("Call", id: "call") {
            MacCallWindow(model: model)
        }
        .defaultSize(width: 420, height: 720)

        Settings {
            MacSettingsView(model: model)
        }
        #endif
    }
}
