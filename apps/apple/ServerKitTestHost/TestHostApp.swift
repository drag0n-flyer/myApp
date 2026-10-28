import SwiftUI

// Empty app that only hosts ServerKit's Apple tests so they run with a keychain entitlement.
// Never shipped: it is not part of project.yml or any release scheme.
@main
struct ServerKitTestHostApp: App {
    var body: some Scene {
        WindowGroup { Text(verbatim: "ServerKit test host") }
    }
}
