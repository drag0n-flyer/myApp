import Testing
@testable import AppCore

// Review Focus 3
@Test func glass() {
    #expect(GlassStyle.resolve(supportsLiquidGlass: true, reduceTransparency: false) == .liquidGlass)
    #expect(GlassStyle.resolve(supportsLiquidGlass: false, reduceTransparency: false) == .material)
    #expect(GlassStyle.resolve(supportsLiquidGlass: true, reduceTransparency: true) == .solid)
    #expect(GlassStyle.resolve(supportsLiquidGlass: false, reduceTransparency: true) == .solid)
}
