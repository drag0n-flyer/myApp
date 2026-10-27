import SwiftUI

/// Photo, name, and status in the navigation bar; opens chat info.
struct ChatHeaderView: View {
    let name: String
    let status: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Avatar(name: name, size: 30)
                VStack(alignment: .leading, spacing: 0) {
                    Text(name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(status).font(.caption2).foregroundStyle(.secondary)
                }
            }
            .padding(.trailing, 4)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(name), \(status)"))
        .accessibilityHint("Shows chat info")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("chat.header")
    }
}
