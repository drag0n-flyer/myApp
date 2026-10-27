/// How glass surfaces are drawn on this device.
public enum GlassStyle: Sendable {
    /// Apple's Liquid Glass (iOS 26 / macOS 26 and later).
    case liquidGlass
    /// System blur materials on older systems.
    case material
    /// Solid backgrounds when the user turned on Reduce Transparency.
    case solid

    public static func resolve(supportsLiquidGlass: Bool, reduceTransparency: Bool) -> GlassStyle {
        if reduceTransparency { return .solid }
        return supportsLiquidGlass ? .liquidGlass : .material
    }
}
