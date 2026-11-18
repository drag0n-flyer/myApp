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
    @MainActor private static let lockTimer = LockDueTimer()

    /// App lock: leaving and returning are tracked here, not in a view, so they count with every window closed.
    func applicationDidResignActive(_ notification: Notification) {
        MainActor.assumeIsolated {
            guard let lock = AppDelegate.model?.lock else { return }
            lock.phaseChanged(.inactive)
            AppDelegate.lockTimer.start(lock)
        }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        MainActor.assumeIsolated {
            AppDelegate.lockTimer.cancel()
            AppDelegate.model?.lock.phaseChanged(.active)
        }
    }

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
                .modifier(LockSceneDriver(lock: model.lock))
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
            LockGate(lock: model.lock) { MacSettingsView(model: model) }
        }
        #endif
    }
}
