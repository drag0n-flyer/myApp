import SwiftUI

/// About 30 bars that scroll as new levels arrive, newest at the trailing edge.
struct VoiceLevelMeter: View {
    let levels: [Float]
    private let barCount = 30

    var body: some View {
        GeometryReader { proxy in
            HStack(spacing: 2) {
                ForEach(0..<barCount, id: \.self) { index in
                    let level = value(at: index)
                    Capsule()
                        .fill(Color.secondary)
                        .frame(height: max(3, CGFloat(level) * proxy.size.height))
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(maxHeight: .infinity, alignment: .center)
        }
        .accessibilityHidden(true)
    }

    /// Missing history on the leading side reads as silence.
    private func value(at index: Int) -> Float {
        let offset = barCount - levels.count
        let i = index - offset
        return i >= 0 && i < levels.count ? levels[i] : 0
    }
}
