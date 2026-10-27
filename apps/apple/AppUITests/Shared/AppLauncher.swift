import XCTest

extension XCUIApplication {
    /// Launches the app with UI-test launch arguments (see `LaunchOptions` in the app).
    static func launched(skipOnboarding: Bool = true, network: String = "mobile", extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        var args = ["-network", network] + extra
        if skipOnboarding { args.append("-skipOnboarding") }
        app.launchArguments = args
        app.launch()
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

extension XCUIElement {
    @discardableResult
    func waitToExist(_ timeout: TimeInterval = 5) -> Bool { waitForExistence(timeout: timeout) }
}
