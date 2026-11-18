import SwiftUI

/// Photo and name in the navigation bar; opens chat info.
struct ChatHeaderView: View {
    let name: String
    let colorKey: String?
    /// "typing…", "online", "last seen 9:38", or nil.
    var status: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Avatar(name: name, colorKey: colorKey, size: 30)
                VStack(alignment: .leading, spacing: 0) {
                    Text(name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if let status {
                        Text(status).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            .padding(.trailing, 4)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(status.map { "\(name), \($0)" } ?? name))
        .accessibilityHint("Shows chat info")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("chat.header")
    }
}
