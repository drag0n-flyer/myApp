import SwiftUI

/// Server name with a connection dot, and the mode a new call would start with.
struct StatusStripView: View {
    let host: String
    let modeLabel: String

    var body: some View {
        HStack {
            Circle().fill(.green).frame(width: 7, height: 7)
            Text(host).lineLimit(1)
            Spacer()
            Label {
                Text(modeLabel)
            } icon: {
                Circle().fill(.green).frame(width: 7, height: 7)
            }
            .foregroundStyle(.green)
            .fontWeight(.semibold)
        }
        .font(.caption)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .glassBackground(in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Connected to \(host). Calls: \(modeLabel)"))
        .accessibilityIdentifier("chatlist.strip")
    }
}
