import SwiftUI

/// Onboarding until the account is ready, then the main app.
struct RootView: View {
    @Bindable var model: AppModel
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
