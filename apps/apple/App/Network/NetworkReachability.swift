import Foundation
import Network

/// Reports whether the device has a network path at all, for the connection status shown in the chat list and in
/// Settings > Server. (`PathMonitor` serves calls; this one only answers "is there a network".)
@MainActor
final class NetworkReachability {
    private let monitor = NWPathMonitor()
    private var started = false

    /// `true` when a path is available.
    var onChange: ((Bool) -> Void)?

    func start() {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in self?.onChange?(satisfied) }
        }
        monitor.start(queue: DispatchQueue(label: "app.status.path"))
    }

    func stop() {
        guard started else { return }
        monitor.cancel()
    }
}
