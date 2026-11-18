import AppCore
import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Tells `AppLock` when the app leaves and returns.
/// - iPhone: from the scene phase; and a separate privacy window covers everything while the scene is not active and
///   the app is not locked (the screens underneath keep their state).
/// - Mac: from `NSApplication` activation (the App-level scene phase often stays active on macOS), plus a timer that
///   locks a window left visible behind other apps once the delay has passed.
struct LockSceneDriver: ViewModifier {
    let lock: AppLock
    #if os(iOS)
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .onChange(of: scenePhase) { _, phase in
                lock.phaseChanged(LockPhase(phase))
                PrivacyWindow.shared.setVisible(lock.showsPrivacyCover)
            }
            .onChange(of: lock.showsPrivacyCover) { _, show in PrivacyWindow.shared.setVisible(show) }
            .onAppear {
                // A launch that starts in the background.
                lock.phaseChanged(LockPhase(scenePhase))
                PrivacyWindow.shared.setVisible(lock.showsPrivacyCover)
            }
    }
    #else
    @State private var timer = LockDueTimer()

    func body(content: Content) -> some View {
        content
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
                lock.phaseChanged(.inactive)
                timer.start(lock)
            }
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
                timer.cancel()
                lock.phaseChanged(.active)
            }
    }
    #endif
}

extension LockPhase {
    init(_ phase: ScenePhase) {
        switch phase {
        case .active: self = .active
        case .background: self = .background
        default: self = .inactive   // inactive, and any phase added later: the safe side
        }
    }
}

#if os(macOS)
/// One main-actor timer per absence: after the effective delay it asks the lock to lock itself (`AppLock.lockIfDue`).
@MainActor
final class LockDueTimer {
    private var task: Task<Void, Never>?

    func start(_ lock: AppLock) {
        task?.cancel()
        guard let seconds = lock.secondsUntilDue else { return }
        task = Task { @MainActor [lock] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            lock.lockIfDue()
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}
#endif

#if os(iOS)
/// A window above everything (alerts included) that shows the app name and nothing else, while the scene is not
/// active and the app is not locked. Not interactive. It hides again when the scene is active, or when the app is
/// locked (the real cover with Unlock is then in the root).
@MainActor
final class PrivacyWindow {
    static let shared = PrivacyWindow()
    private var window: UIWindow?

    func setVisible(_ visible: Bool) {
        guard visible else {
            window?.isHidden = true
            window = nil
            return
        }
        guard window == nil else { return }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive })
            ?? scenes.first(where: { $0.activationState == .foregroundInactive }) ?? scenes.first else { return }
        let privacy = UIWindow(windowScene: scene)
        privacy.windowLevel = UIWindow.Level.alert + 1
        let host = UIHostingController(rootView: PrivacyCoverView())
        host.view.isUserInteractionEnabled = false
        privacy.rootViewController = host
        privacy.isUserInteractionEnabled = false
        privacy.isHidden = false
        window = privacy
    }
}

/// The look of `LockCoverView` without the Unlock button.
private struct PrivacyCoverView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.fill")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(AppBrand.name)
                .font(.title.weight(.semibold))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
        .accessibilityHidden(true)
    }
}
#endif
