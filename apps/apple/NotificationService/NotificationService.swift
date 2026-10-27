import AppCore
import Messaging
import UserNotifications

/// Notification Service Extension: decrypts the push, stores the message for the app, and rewrites
/// the notification. Never log push data, message text or names.
///
/// Limits: about 24 MB of memory and 30 s. `NotificationBudget` holds the numbers; one push is
/// processed per launch. The content handler is called exactly once: by the processing task, or
/// by `serviceExtensionTimeWillExpire` with the generic fallback, whichever comes first.
final class NotificationService: UNNotificationServiceExtension {
    private let delivery = Delivery()

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        delivery.start(handler: contentHandler, content: content)
        // Check the push before opening anything: a malformed or oversize `d` costs no memory.
        guard let push = NotificationBudget.decodePush(request.content.userInfo["d"]) else {
            delivery.finish(.fallback)
            return
        }
        let deadline = NotificationBudget.deadline(from: Date())
        let delivery = delivery
        delivery.keep(Task.detached(priority: .userInitiated) {
            let outcome = await NSEWiring.process(pushData: push, deadline: deadline)
            delivery.finish(outcome)
        })
    }

    override func serviceExtensionTimeWillExpire() {
        delivery.expire()
    }
}

/// Owns the system's content handler and the best-attempt content. All access goes through the
/// lock; the `OnceGate` makes sure the handler runs exactly once.
private final class Delivery: @unchecked Sendable {
    private let lock = NSLock()
    private let gate = OnceGate()
    private var handler: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?
    private var task: Task<Void, Never>?
    private let strings = NotificationStrings.localized()

    func start(handler: @escaping (UNNotificationContent) -> Void, content: UNMutableNotificationContent) {
        lock.withLock {
            self.handler = handler
            self.content = content
        }
    }

    func keep(_ task: Task<Void, Never>) {
        lock.withLock { self.task = task }
    }

    /// Time is up: stop working and show the generic notice. A decrypted outcome that already won
    /// the gate is left alone.
    func expire() {
        lock.withLock { task }?.cancel()
        deliver(.fallback(strings))
    }

    func finish(_ outcome: NotificationOutcome) {
        deliver(NotificationPresentation(outcome: outcome, filtering: Self.filtering, strings: strings))
    }

    #if NSE_FILTERING_YES
    private static let filtering = true
    #else
    private static let filtering = false
    #endif

    private func deliver(_ presentation: NotificationPresentation) {
        gate.run {
            let (handler, content) = lock.withLock { (self.handler, self.content) }
            guard let handler else { return }
            switch presentation {
            case .empty:
                handler(UNNotificationContent())
            case .content(let title, let body, let threadID, let sound):
                guard let content else { return handler(UNNotificationContent()) }
                content.title = title
                content.body = body
                if let threadID { content.threadIdentifier = threadID }
                content.sound = sound ? UNNotificationSound.default : nil
                handler(content)
            case .call(let payload, let contact):
                guard let content else { return handler(UNNotificationContent()) }
                NotificationService.applyCall(payload, from: contact, to: content)
                handler(content)
            }
        }
    }
}
