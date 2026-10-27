import AppCore

extension AudioProfile {
    /// The single source for data mode to wire settings. Plan 5 measurements may change these values.
    public static let table: [DataMode: AudioProfile] = [
        .ultraLow: AudioProfile(mode: .ultraLow, maxAverageBitrate: 10_000, ptime: 60, maxPlaybackRate: 16_000,
                                wireBudget: 21_000, inbandFEC: false, dtx: true),
        .dataSaver: AudioProfile(mode: .dataSaver, maxAverageBitrate: 16_000, ptime: 40, maxPlaybackRate: 16_000,
                                 wireBudget: 30_000, inbandFEC: true, dtx: true),
        .balanced: AudioProfile(mode: .balanced, maxAverageBitrate: 24_000, ptime: 20, maxPlaybackRate: 48_000,
                                wireBudget: 51_000, inbandFEC: true, dtx: true),
        .high: AudioProfile(mode: .high, maxAverageBitrate: 40_000, ptime: 20, maxPlaybackRate: 48_000,
                            wireBudget: 75_000, inbandFEC: true, dtx: true),
    ]

    public static func `for`(_ mode: DataMode) -> AudioProfile { table[mode]! }
}
