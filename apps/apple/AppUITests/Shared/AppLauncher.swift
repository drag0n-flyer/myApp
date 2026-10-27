import XCTest

@MainActor
extension XCUIApplication {
    /// Launches the app with UI-test launch arguments (see `LaunchOptions` in the app).
    static func launched(skipOnboarding: Bool = true, network: String = "mobile", extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        var args = ["-ApplePersistenceIgnoreState", "YES", "-uiTesting", "-sampleData", "-network", network] + extra
        if skipOnboarding { args.append("-skipOnboarding") }
        app.launchArguments = args
        app.launch()
        #if os(macOS)
        // A freshly launched Mac app on a CI runner may not be frontmost; make sure its window is up.
        app.activate()
        if !app.windows.firstMatch.waitForExistence(timeout: 10) {
            // SwiftUI did not open the main window on this runner: ask for one through File > New Window.
            print("No window after launch; using File > New Window")
            app.menuBars.menuBarItems["File"].click()
            app.menuBars.menuItems["New Window"].click()
            if !app.windows.firstMatch.waitForExistence(timeout: 10) {
                print("Still no window: \(app.debugDescription)")
            }
        }
        #endif
        return app
    }

    func element(_ identifier: String) -> XCUIElement {
        descendants(matching: .any)[identifier].firstMatch
    }

    /// Tab bar button by its visible title ("Chats", "Calls", ...).
    func tab(_ title: String) -> XCUIElement {
        tabBars.buttons[title].firstMatch
    }
}

@MainActor
extension XCUIElement {
    @discardableResult
    func waitToExist(_ timeout: TimeInterval = 5) -> Bool {
        let found = waitForExistence(timeout: timeout)
        // Put the accessibility tree in the CI log when an element is missing.
        if !found { print("Element missing: \(debugDescription)") }
        return found
    }

    /// Swipes up (at most `maxSwipes` times) until the element exists; for rows in lazy lists.
    func scrollToExist(in app: XCUIApplication, maxSwipes: Int = 6) -> Bool {
        if waitForExistence(timeout: 2) { return true }
        for _ in 0..<maxSwipes {
            app.swipeUp()
            if exists { return true }
        }
        return waitToExist(2)
    }
}
