import SwiftUI

private struct ExpectReturnKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

private struct CancelExpectReturnKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

extension EnvironmentValues {
    /// Call right before the app opens its own picker, camera or viewer: the person will come back from it, so that
    /// one absence gets the longer allowance (`AppLock.expectReturn()`). Does nothing by default.
    var expectReturn: @MainActor () -> Void {
        get { self[ExpectReturnKey.self] }
        set { self[ExpectReturnKey.self] = newValue }
    }

    /// Call when that picker, camera or viewer closed (`AppLock.cancelExpectedReturn()`): the allowance ends.
    var cancelExpectReturn: @MainActor () -> Void {
        get { self[CancelExpectReturnKey.self] }
        set { self[CancelExpectReturnKey.self] = newValue }
    }
}

private struct ExpectsReturn: ViewModifier {
    let active: Bool
    @Environment(\.expectReturn) private var expectReturn
    @Environment(\.cancelExpectReturn) private var cancelExpectReturn

    func body(content: Content) -> some View {
        content.onChange(of: active) { _, on in
            if on { expectReturn() } else { cancelExpectReturn() }
        }
    }
}

extension View {
    /// Tells the app lock that the person is about to leave for the app's own picker, camera or viewer while `active`
    /// turns true, and will come back; when it turns false again the allowance ends.
    func expectsReturn(when active: Bool) -> some View { modifier(ExpectsReturn(active: active)) }
}
