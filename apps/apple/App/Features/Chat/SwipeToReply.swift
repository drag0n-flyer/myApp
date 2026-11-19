import SwiftUI

#if os(iOS)
/// Drag a bubble toward the trailing side to reply, like the Reply item of the context menu.
/// The drag is horizontal only and starts after 20 pt, so scrolling and the long-press menu keep working.
private struct SwipeToReply: ViewModifier {
    let enabled: Bool
    let reply: () -> Void

    static let maxOffset: CGFloat = 64
    static let threshold: CGFloat = 48

    @Environment(\.layoutDirection) private var layoutDirection
    @State private var offset: CGFloat = 0
    @State private var isHorizontal: Bool?
    @State private var pastThreshold = false

    func body(content: Content) -> some View {
        if enabled {
            content
                .offset(x: offset * direction)
                .background(alignment: .leading) {
                    Image(systemName: "arrowshape.turn.up.left")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(pastThreshold ? Color.accentColor : Color.secondary)
                        .opacity(Double(min(offset / Self.threshold, 1)))
                        .scaleEffect(pastThreshold ? 1.15 : 0.8)
                        .padding(.leading, 8)
                        .accessibilityHidden(true)
                }
                .simultaneousGesture(drag)
                .sensoryFeedback(.selection, trigger: pastThreshold) { _, now in now }
        } else {
            content
        }
    }

    /// +1 when the trailing side is to the right, -1 in right-to-left layouts.
    private var direction: CGFloat { layoutDirection == .rightToLeft ? -1 : 1 }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 20)
            .onChanged { value in
                if isHorizontal == nil {
                    isHorizontal = abs(value.translation.width) > abs(value.translation.height) * 1.5
                }
                guard isHorizontal == true else { return }
                let travel = max(0, value.translation.width * direction)
                offset = min(travel, Self.maxOffset)
                let past = travel >= Self.threshold
                if past != pastThreshold { pastThreshold = past }
            }
            .onEnded { _ in
                let fire = isHorizontal == true && pastThreshold
                isHorizontal = nil
                withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) {
                    offset = 0
                    pastThreshold = false
                }
                if fire { reply() }
            }
    }
}
#endif

extension View {
    /// Swipe-to-reply on iPhone and iPad; a no-op on Mac, which keeps the context menu.
    @ViewBuilder
    func swipeToReply(enabled: Bool, reply: @escaping () -> Void) -> some View {
        #if os(iOS)
        modifier(SwipeToReply(enabled: enabled, reply: reply))
        #else
        self
        #endif
    }
}
