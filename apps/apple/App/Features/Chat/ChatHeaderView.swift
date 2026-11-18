import SwiftUI

/// Photo and name in the navigation bar; opens chat info.
struct ChatHeaderView: View {
    let name: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Avatar(name: name, size: 30)
                Text(name).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            .padding(.trailing, 4)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(name))
        .accessibilityHint("Shows chat info")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("chat.header")
    }
}
