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
            if model.onboarding.step == .done {
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
        .alert("This copy of the app is set up incorrectly, so it can't sign in. You can look around with sample chats.",
               isPresented: $model.setupProblemAlert) {
            Button("OK", role: .cancel) {}
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
