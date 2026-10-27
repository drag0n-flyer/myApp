#if os(macOS)
import AppKit
import AppCore
import CallCore
import UserNotifications

/// What the Mac ring needs from the call engine. `AppModel` conforms for now; `LiveCallEngine` takes over later.
/// All calls arrive on the main actor.
@MainActor
protocol MacRingHandling: AnyObject, Sendable {
    /// Shows or clears the in-app incoming-call banner.
    func macRingSetCaller(_ caller: Contact?)
    /// `caller` is nil when the app was launched by the notification (the engine knows the call by `callID`).
    func macRingAnswer(callID: String, caller: Contact?, video: Bool)
    func macRingDecline(callID: String)
    /// `call.status`, or nil when it cannot be fetched.
    func macRingStatus(callID: String) async -> CallStatus?
    /// The call was already over when the user answered, or the ring ran out: record it as missed or ended.
    func macRingFinished(callID: String, kind: CallKitEndKind)
}

/// Rings the Mac for an incoming call (macOS has no CallKit for VoIP): a time-sensitive notification with
/// Answer and Decline, the in-app banner, a looping sound, and a Dock badge. Decisions (one ring at a time,
/// the 45 s cap, stale or ended calls, each action once) live in `MacRingTracker`.
@MainActor
final class MacRinger: NSObject, UNUserNotificationCenterDelegate {
    static let categoryID = "CALL"
    static let answerID = "ANSWER"
    static let declineID = "DECLINE"

    private struct Ringing { var callID: String; var caller: Contact; var video: Bool; var at: Date }

    private weak var handler: MacRingHandling?
    private let center: UNUserNotificationCenter?
    private let plays: Bool
    private var tracker = MacRingTracker()
    private var ringing: Ringing?
    private var soundTask: Task<Void, Never>?
    private var capTask: Task<Void, Never>?

    /// - systemUI: false in UI tests: no notification, sound or Dock badge, so no permission prompt or noise.
    init(handler: MacRingHandling, systemUI: Bool = true) {
        self.handler = handler
        self.plays = systemUI
        self.center = systemUI ? UNUserNotificationCenter.current() : nil
        super.init()
        guard let center else { return }
        center.delegate = self
        let answer = UNNotificationAction(identifier: Self.answerID, title: String(localized: "Answer"), options: [.foreground])
        let decline = UNNotificationAction(identifier: Self.declineID, title: String(localized: "Decline"), options: [.destructive])
        let category = UNNotificationCategory(identifier: Self.categoryID, actions: [answer, decline],
                                              intentIdentifiers: [], options: [])
        center.setNotificationCategories([category])
    }

    // MARK: Ringing

    /// A `call.ring` for a contact. Strangers never reach here (`ChatStore.incomingCall` records them as missed).
    func ring(callID: String, caller: Contact, video: Bool, at: Date = Date()) {
        guard tracker.ring(callID: callID, at: at) else { return }
        ringing = Ringing(callID: callID, caller: caller, video: video, at: at)
        handler?.macRingSetCaller(caller)
        post(callID: callID, name: caller.displayName, video: video, ringAge: 0, at: at)
        guard plays else { return }
        NSApp.dockTile.badgeLabel = "1"
        startSound()
        // Monotonic sleep; the timer firing is the cap (no wall-clock re-check).
        let delay = tracker.capDelay(callID: callID, now: Date()) ?? CallNotificationText.staleAfter
        capTask = Task { [weak self] in
            try? await ContinuousClock().sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            self?.capFired(callID: callID)
        }
    }

    /// The call ended (caller hung up, answered on another device, declined elsewhere).
    func ended(callID: String) {
        guard tracker.ended(callID: callID) else { return }
        stopRinging()
        center?.removeDeliveredNotifications(withIdentifiers: [callID])
    }

    /// Banner buttons.
    func answerFromBanner() { act(.answer, callID: ringing?.callID) }
    func declineFromBanner() { act(.decline, callID: ringing?.callID) }

    private func capFired(callID: String) {
        guard let r = ringing, r.callID == callID, tracker.capFired(callID: callID) else { return }
        stopRinging()
        post(callID: callID, name: r.caller.displayName, video: r.video, ringAge: CallNotificationText.staleAfter, at: r.at)
        handler?.macRingFinished(callID: callID, kind: .unanswered)
    }

    private func stopRinging() {
        ringing = nil
        soundTask?.cancel(); soundTask = nil
        capTask?.cancel(); capTask = nil
        handler?.macRingSetCaller(nil)
        if plays { NSApp.dockTile.badgeLabel = nil }
    }

    // MARK: Actions

    private func act(_ action: MacNotificationAction, callID: String?, ringedAt: Date? = nil, video: Bool? = nil) {
        guard let callID else { return }
        let at = ringedAt ?? ringing?.at ?? Date()
        let isVideo = video ?? ringing?.video ?? false
        Task { [weak self] in
            guard let self, let handler = self.handler else { return }
            let caller = self.ringing?.caller
            let status = await Self.status(of: callID, via: handler)
            let outcome = self.tracker.route(action, callID: callID, ringedAt: at, status: status, now: Date())
            if outcome.stopsRinging {
                self.stopRinging()
                self.center?.removeDeliveredNotifications(withIdentifiers: [callID])
            }
            switch outcome.route {
            case .answer(let id): handler.macRingAnswer(callID: id, caller: caller, video: isVideo)
            case .decline(let id): handler.macRingDecline(callID: id)
            case .expired(let id, let kind): handler.macRingFinished(callID: id, kind: kind)
            case .ignore: break
            }
        }
    }

    /// `call.status` within 5 s; nil on timeout (the call is treated as still ringing).
    private static func status(of callID: String, via handler: MacRingHandling) async -> CallStatus? {
        await withTaskGroup(of: CallStatus?.self) { group in
            group.addTask { @MainActor in await handler.macRingStatus(callID: callID) }
            group.addTask { try? await Task.sleep(for: .seconds(5)); return nil }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    // MARK: Notification and sound

    private func post(callID: String, name: String, video: Bool, ringAge: TimeInterval, at: Date) {
        guard let center else { return }
        let text = CallNotificationText.content(callerName: name, video: video, ringAge: ringAge)
        let content = UNMutableNotificationContent()
        content.title = text.title
        content.body = String(localized: String.LocalizationValue(text.body))
        content.userInfo = ["callID": callID, "video": video, "ringedAt": at.timeIntervalSince1970]
        if text.isCall {
            content.categoryIdentifier = Self.categoryID
            content.interruptionLevel = .timeSensitive
        }
        center.add(UNNotificationRequest(identifier: callID, content: content, trigger: nil))
    }

    /// The system has no public ringtone on macOS: repeat a system alert sound until the ring ends.
    private func startSound() {
        soundTask?.cancel()
        soundTask = Task {
            while !Task.isCancelled {
                NSSound(named: NSSound.Name("Glass"))?.play()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    /// In the foreground the banner is already on screen and the ringer plays its own sound.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        [.banner, .list]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let callID = info["callID"] as? String
        let video = info["video"] as? Bool ?? false
        // A push rewritten by the notification extension has no `ringedAt`: the notification's own date is the ring time.
        let ringedAt = (info["ringedAt"] as? Double).map(Date.init(timeIntervalSince1970:)) ?? response.notification.date
        let action: MacNotificationAction? = switch response.actionIdentifier {
        case Self.answerID: .answer
        case Self.declineID: .decline
        default: nil   // a tap on the body only brings the app forward
        }
        guard let action, let callID else { return }
        await MainActor.run { self.act(action, callID: callID, ringedAt: ringedAt, video: video) }
    }
}
#endif
