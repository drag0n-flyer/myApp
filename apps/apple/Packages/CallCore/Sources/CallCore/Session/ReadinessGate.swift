import Foundation

/// Opens when the signed-in session can handle calls (live store and call engine in place), closes at sign-out.
/// A VoIP push that wakes a killed app waits here, for a bounded time, before its payload is opened.
public actor ReadinessGate {
    private var isOpen = false
    private var waiters: [UUID: CheckedContinuation<Bool, Never>] = [:]

    public init() {}

    public func open() {
        isOpen = true
        let all = waiters
        waiters = [:]
        for (_, w) in all { w.resume(returning: true) }
    }

    public func close() { isOpen = false }

    /// True once the gate is open; false when `timeout` passes first.
    public func wait(timeout: Duration) async -> Bool {
        if isOpen { return true }
        let id = UUID()
        let timer = Task { [weak self] in
            try? await Task.sleep(for: timeout)
            await self?.expire(id)
        }
        defer { timer.cancel() }
        return await withCheckedContinuation { waiters[id] = $0 }
    }

    private func expire(_ id: UUID) {
        waiters.removeValue(forKey: id)?.resume(returning: false)
    }
}

/// How an in-app End (or Decline) leaves a call the system call UI knows about.
public enum InAppHangup: Equatable, Sendable {
    /// Ask the system to end the call (`CXEndCallAction`); its action then hangs up the call.
    case requestSystemEnd
    /// No system call (or the request failed): hang up directly.
    case hangUpHere

    public static func route(systemKnowsCall: Bool) -> InAppHangup {
        systemKnowsCall ? .requestSystemEnd : .hangUpHere
    }
}
