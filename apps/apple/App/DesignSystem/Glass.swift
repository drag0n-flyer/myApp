import AppCore
import SwiftUI

private struct GlassBackground<S: Shape>: ViewModifier {
    let shape: S
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    private var style: GlassStyle {
        var supportsLiquidGlass = false
        if #available(iOS 26, macOS 26, *) { supportsLiquidGlass = true }
        return GlassStyle.resolve(supportsLiquidGlass: supportsLiquidGlass, reduceTransparency: reduceTransparency)
    }

    func body(content: Content) -> some View {
        switch style {
        case .liquidGlass:
            if #available(iOS 26, macOS 26, *) {
                content.glassEffect(.regular, in: shape)
            } else {
                content.background(.ultraThinMaterial, in: shape)
            }
        case .material:
            content.background(.ultraThinMaterial, in: shape)
        case .solid:
            content.background(Color.secondaryBackground, in: shape)
        }
    }
}

extension View {
    /// Liquid Glass on iOS 26 / macOS 26, system material before that, solid with Reduce Transparency.
    func glassBackground<S: Shape>(in shape: S) -> some View {
        modifier(GlassBackground(shape: shape))
    }
}

extension Color {
    static var secondaryBackground: Color {
        #if os(macOS)
        Color(nsColor: .controlBackgroundColor)
        #else
        Color(uiColor: .secondarySystemBackground)
        #endif
    }

    static var groupedBackground: Color {
        #if os(macOS)
        Color(nsColor: .windowBackgroundColor)
        #else
        Color(uiColor: .systemGroupedBackground)
        #endif
    }
}

/// Round glass icon button used in toolbars and headers.
struct GlassCircleButton: View {
    let systemImage: String
    let label: LocalizedStringKey
    var size: CGFloat = 36
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.tint)
        .glassBackground(in: Circle())
        .accessibilityLabel(Text(label))
    }
}
