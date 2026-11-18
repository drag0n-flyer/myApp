/// Which of the 8 tile colours a person gets: FNV-1a 32-bit over the account ID's UTF-8 bytes, modulo 8.
/// Android computes the same, so a person has the same colour on every device.
public enum AvatarColor {
    public static let count = 8

    private static let offsetBasis: UInt32 = 2_166_136_261
    private static let prime: UInt32 = 16_777_619

    public static func index(_ accountID: String) -> Int {
        var hash = offsetBasis
        for byte in accountID.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* prime
        }
        return Int(hash % UInt32(count))
    }
}

/// The 8 letter-tile colours as 0xRRGGBB (index = `AvatarColor.index(accountID)`), one set per colour scheme. The
/// letter on them is always white; every colour has a contrast ratio of at least 4.5 to 1 against white.
/// Hues in order: red, orange, olive, green, teal, blue, violet, magenta.
public enum AvatarTiles {
    public static let light: [UInt32] = [
        0xB3261E, 0xA14A00, 0x5C6B00, 0x1B6E3A, 0x00696B, 0x1A5FB4, 0x5B4BB8, 0xA8327A,
    ]
    public static let dark: [UInt32] = [
        0xA8453F, 0x9B5A1A, 0x6B7A1F, 0x2F7D4A, 0x0F7578, 0x3F6FB5, 0x7566C9, 0xB04A8E,
    ]

    /// The tile for the person with `key` (their account ID); `nil` (the neutral tile) when there is no key.
    public static func tile(for key: String?, dark isDark: Bool) -> UInt32? {
        guard let key, !key.isEmpty else { return nil }
        return (isDark ? dark : light)[AvatarColor.index(key)]
    }
}
