import SwiftUI

private struct ExpectReturnKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

extension EnvironmentValues {
    /// Call right before the app opens its own picker, camera or viewer: the person will come back from it, so that
    /// one absence gets the longer allowance (`AppLock.expectReturn()`). Does nothing by default.
    var expectReturn: @MainActor () -> Void {
        get { self[ExpectReturnKey.self] }
        set { self[ExpectReturnKey.self] = newValue }
    }
}

private struct ExpectsReturn: ViewModifier {
    let active: Bool
    @Environment(\.expectReturn) private var expectReturn

    func body(content: Content) -> some View {
        content.onChange(of: active) { _, on in if on { expectReturn() } }
    }
}

extension View {
    /// Tells the app lock that the person is about to leave for the app's own picker, camera or viewer while `active`
    /// turns true, and will come back.
    func expectsReturn(when active: Bool) -> some View { modifier(ExpectsReturn(active: active)) }
}
