import SwiftUI

@main
struct AppMain: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .onOpenURL { model.handle(url: $0) }
        }
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
