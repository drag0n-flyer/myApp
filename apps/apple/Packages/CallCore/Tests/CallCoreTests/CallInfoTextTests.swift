import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private func state(mode: DataMode, bytes: Int64) -> CallState {
    CallState(contact: bob, phase: .active, isVideoCall: false, isVideoOn: false, bytesUsed: bytes,
              effectiveMode: mode, network: .wifi)
}

@Suite struct CallInfoTextTests {
    @Test func infoRows() {
        let stats = MediaStats(roundTripTime: 0.123, inboundLossFraction: 0.031, usingRelay: true)
        let rows = CallInfoText.rows(stats: stats, state: state(mode: .dataSaver, bytes: 1_500_000))
        #expect(rows.map(\.label) == ["Connection", "Delay", "Sound lost", "Quality", "Data used"])
        #expect(rows.map(\.value) == ["Through your server", "123 ms", "3%", "Data saver", "1.5 MB"])
    }

    @Test func directConnectionAndMissingDelay() {
        let rows = CallInfoText.rows(stats: MediaStats(), state: state(mode: .high, bytes: 0))
        #expect(rows.map(\.value) == ["Direct", "—", "0%", "High quality", "0 KB"])
    }

    @Test func infoRowsPlainWords() {
        for relay in [true, false] {
            for mode in DataMode.allCases {
                let stats = MediaStats(roundTripTime: 0.05, inboundLossFraction: 0.5, usingRelay: relay, audioCodec: "opus")
                let rows = CallInfoText.rows(stats: stats, state: state(mode: mode, bytes: 12_345_678))
                for text in rows.flatMap({ [$0.label, $0.value] }) {
                    for banned in ["codec", "kbps", "relay", "opus"] {
                        #expect(!text.lowercased().contains(banned))
                    }
                }
            }
        }
    }
}
