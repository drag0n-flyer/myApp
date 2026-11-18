import Foundation
import Testing
@testable import AppCore

@Test func avatarColorKnownIdsKeepTheirColour() {
    // Same values as the Kotlin test: FNV-1a 32 over UTF-8, modulo 8.
    #expect(AvatarColor.index("a") == 4)
    #expect(AvatarColor.index("acc-ana") == 5)
    #expect(AvatarColor.index("3f2b8c1e-9d4a-4e7b-a1c5-0b6d7e8f9a12") == 2)
    #expect(AvatarColor.index("") == 5)
}

@Test func avatarColorUsesUtf8BytesNotUtf16Units() {
    #expect(AvatarColor.index("é") == 1)
}

@Test func avatarColorAlwaysInRange() {
    for i in 0..<2000 { #expect((0..<AvatarColor.count).contains(AvatarColor.index("account-\(i)"))) }
    #expect(AvatarColor.count == 8)
}

private func luminance(_ hex: UInt32) -> Double {
    func channel(_ v: UInt32) -> Double {
        let s = Double(v) / 255
        return s <= 0.03928 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel((hex >> 16) & 0xFF) + 0.7152 * channel((hex >> 8) & 0xFF) + 0.0722 * channel(hex & 0xFF)
}

@Test func avatarTilesHaveEightColoursEachWithAtLeast45ContrastAgainstWhite() {
    #expect(AvatarTiles.light.count == 8)
    #expect(AvatarTiles.dark.count == 8)
    #expect(AvatarTiles.light[0] == 0xB3261E && AvatarTiles.dark[7] == 0xB04A8E)
    let white = luminance(0xFFFFFF)
    for hex in AvatarTiles.light + AvatarTiles.dark {
        let ratio = (white + 0.05) / (luminance(hex) + 0.05)
        #expect(ratio >= 4.5, "\(String(hex, radix: 16)) has ratio \(ratio)")
    }
}

@Test func avatarTilePicksByKeyAndIsNeutralWithoutOne() {
    #expect(AvatarTiles.tile(for: "a", dark: false) == AvatarTiles.light[4])
    #expect(AvatarTiles.tile(for: "a", dark: true) == AvatarTiles.dark[4])
    #expect(AvatarTiles.tile(for: nil, dark: false) == nil)
    #expect(AvatarTiles.tile(for: "", dark: true) == nil)
}
