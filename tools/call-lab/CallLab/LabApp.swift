import SwiftUI

// NOTE: the Call Lab app target is not compiled on Linux. Everything under CallLab/ is UNVERIFIED
// until the WebRTC release exists and the call-lab workflow's `build` job is green.

@main
struct LabApp: App {
    @StateObject private var call = LabCall()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                ConnectView()
            }
            .environmentObject(call)
        }
        #if os(macOS)
        Window("Rig", id: "rig") { RigView() }
        #endif
    }
}
