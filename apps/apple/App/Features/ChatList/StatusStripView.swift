import AppCore
import SwiftUI

/// Server name with a connection dot, and the mode a new call would start with. The dot and text follow the live
/// connection: green with the host when connected, grey while connecting, grey with "No connection" when offline.
struct StatusStripView: View {
    let host: String
    let modeLabel: String
    let status: ConnectionStatus

    var body: some View {
        HStack {
            Circle().fill(status == .connected ? Color.green : Color.gray).frame(width: 7, height: 7)
            title.lineLimit(1)
            Spacer()
            if status == .connected {
                Label {
                    Text(modeLabel)
                } icon: {
                    Circle().fill(.green).frame(width: 7, height: 7)
                }
                .foregroundStyle(.green)
                .fontWeight(.semibold)
            }
        }
        .font(.caption)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .glassBackground(in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityIdentifier("chatlist.strip")
    }

    private var title: Text {
        switch status {
        case .connected: Text(host)
        case .connecting: Text("Connecting…")
        case .offline: Text("No connection")
        }
    }

    private var accessibilityText: Text {
        switch status {
        case .connected: Text("Connected to \(host). Calls: \(modeLabel)")
        case .connecting: Text("Connecting…")
        case .offline: Text("No connection")
        }
    }
}
