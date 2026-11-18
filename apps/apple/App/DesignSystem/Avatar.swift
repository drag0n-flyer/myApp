import AppCore
import SwiftUI

extension Color {
    /// A colour from 0xRRGGBB.
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    /// The tile colour of the person with this account ID (`AvatarTiles`), by colour scheme. White letters go on it.
    static func avatarTile(for colorKey: String?, dark: Bool) -> Color {
        guard let hex = AvatarTiles.tile(for: colorKey, dark: dark) else { return neutralAvatarTile }
        return Color(hex: hex)
    }

    /// The tile when there is no person (contrast with white is above 4.5 to 1).
    static let neutralAvatarTile = Color(hex: 0x6E6E73)
}

/// Initial-letter avatar on a coloured tile. The colour comes from the person's stable account ID (`colorKey`),
/// never from their name, so it is the same on every screen and every device. Pass `nil` only where there is no
/// person to colour (the tile is then neutral).
struct Avatar: View {
    let name: String
    let colorKey: String?
    var size: CGFloat = 46
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Circle()
            .fill(Color.avatarTile(for: colorKey, dark: colorScheme == .dark))
            .frame(width: size, height: size)
            .overlay {
                Text(String(name.first ?? "?"))
                    .font(.system(size: size * 0.38, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
    }
}
