import AppCore
import SwiftUI

/// What the root shows while the app is locked: the app name and an Unlock button. Nothing of any chat.
struct LockCoverView: View {
    var lock: AppLock

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.fill")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(AppBrand.name)
                .font(.title.weight(.semibold))
            // Shown only while locked; the brief cover of the app switcher has nothing to press.
            if lock.isLocked {
                Button {
                    Task { await lock.unlockWithPrompt(reason: reason) }
                } label: {
                    Text("Unlock")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityLabel(Text("Unlock \(AppBrand.name)"))
                .accessibilityIdentifier("lock.unlock")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
        .accessibilityIdentifier("lock.cover")
        // The prompt appears by itself once per request, and only while the scene is active.
        .task(id: lock.promptKey) { await lock.promptAutomatically(reason: reason) }
    }

    private var reason: String { String(localized: "Unlock \(AppBrand.name)") }
}

/// Wraps a screen (the Mac Settings window): only the cover while the app is covered.
struct LockGate<Content: View>: View {
    var lock: AppLock
    @ViewBuilder var content: () -> Content

    var body: some View {
        if lock.coversContent {
            LockCoverView(lock: lock)
        } else {
            content()
        }
    }
}
