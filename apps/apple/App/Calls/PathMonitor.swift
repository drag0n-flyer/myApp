import Foundation
import Network
import AppCore
import CallCore

/// Watches the network path. Reports every change of network type, and flags changes that matter to a call
/// (the network came or went, or the main interface changed) using CallCore's `isMeaningfulChange`.
@MainActor
final class PathMonitor {
    private let monitor = NWPathMonitor()
    private var last: PathSnapshot?
    private var started = false

    /// `meaningful` is true when a running call should restart ICE.
    var onChange: ((_ network: NetworkType, _ meaningful: Bool) -> Void)?

    func start() {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { [weak self] path in
            let snapshot = Self.snapshot(path)
            // Expensive paths (cellular, personal hotspot) count as mobile data.
            let network: NetworkType = path.isExpensive ? .mobile : .wifi
            Task { @MainActor in self?.apply(snapshot, network) }
        }
        monitor.start(queue: DispatchQueue(label: "app.calls.path"))
    }

    func stop() {
        guard started else { return }
        monitor.cancel()
    }

    private func apply(_ snapshot: PathSnapshot, _ network: NetworkType) {
        let meaningful = last.map { isMeaningfulChange(from: $0, to: snapshot) } ?? false
        last = snapshot
        onChange?(network, meaningful)
    }

    private nonisolated static func snapshot(_ path: NWPath) -> PathSnapshot {
        let interface: String
        if path.usesInterfaceType(.wifi) { interface = "wifi" }
        else if path.usesInterfaceType(.cellular) { interface = "cellular" }
        else if path.usesInterfaceType(.wiredEthernet) { interface = "wired" }
        else { interface = "other" }
        return PathSnapshot(interface: interface, isSatisfied: path.status == .satisfied)
    }
}
