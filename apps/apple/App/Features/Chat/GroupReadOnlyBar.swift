import AppCore
import SwiftUI

/// Where the composer was, in a group I was removed from or left: the chat and its history stay, nothing can be sent.
struct GroupReadOnlyBar: View {
    var body: some View {
        Text("You're no longer in this group")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .padding(.horizontal)
            .background(.bar)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("chat.readonly")
    }
}
