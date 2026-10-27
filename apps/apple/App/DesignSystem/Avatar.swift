import SwiftUI

/// Initial-letter avatar with a stable color per person.
struct Avatar: View {
    let name: String
    var size: CGFloat = 46

    private static let palette: [(Color, Color)] = [
        (.cyan, .blue), (.green, .teal), (.orange, .yellow), (.purple, .indigo),
        (.pink, .red), (.mint, .green), (.indigo, .purple), (.yellow, .orange),
    ]

    private var colors: (Color, Color) {
        let sum = name.unicodeScalars.reduce(0) { $0 + Int($1.value) }
        return Self.palette[sum % Self.palette.count]
    }

    var body: some View {
        Circle()
            .fill(LinearGradient(colors: [colors.0, colors.1], startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay {
                Text(String(name.first ?? "?"))
                    .font(.system(size: size * 0.38, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
    }
}
