import SwiftUI

/// Onboarding until the account is ready, then the main app.
struct RootView: View {
    @Bindable var model: AppModel
    @AppStorage("appearance") private var appearance = AppearanceChoice.system.rawValue
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    var body: some View {
        Group {
            if model.hasBrokenSetup {
                // Blocking: a build without its shared groups can neither sign in nor show sample chats.
                ContentUnavailableView {
                    Label("This copy of the app is set up incorrectly", systemImage: "exclamationmark.triangle")
                } description: {
                    Text("It can't sign in or show chats. Install the app again to fix it.")
                }
                .accessibilityIdentifier("setup.broken")
            } else if model.onboarding.step == .done {
                #if os(macOS)
                MacRootView(model: model)
                #else
                MainTabView(model: model)
                #endif
            } else {
                OnboardingView(model: model)
            }
        }
        .preferredColorScheme(AppearanceChoice(rawValue: appearance)?.colorScheme)
        .alert("This device was removed from your account.", isPresented: $model.deviceRemovedAlert) {
            Button("OK", role: .cancel) {}
        }
        .alert("Your chats couldn't be opened", isPresented: $model.liveStoreUnavailable) {
            Button("Try again") { Task { await model.retryLiveStore() } }
        } message: {
            Text("Check your connection, then try again.")
        }
        #if os(iOS)
        .fullScreenCover(isPresented: isCallPresented) {
            CallView(model: model)
        }
        #else
        .onChange(of: model.activeCall != nil) { _, hasCall in
            if hasCall { openWindow(id: "call") }
        }
        #endif
    }

    private var isCallPresented: Binding<Bool> {
        Binding(
            get: { model.activeCall != nil },
            set: { presented in
                if !presented {
                    if model.activeCall?.isEnded == false { model.calls.end() }
                    model.dismissEndedCall()
                }
            }
        )
    }
}
