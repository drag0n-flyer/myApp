public enum LabPreset: String, CaseIterable, Sendable {
    case ultraLow, dataSaver, balanced, high

    public var knobs: LabKnobs {
        switch self {
        case .ultraLow:
            LabKnobs(opusBitrate: 9_000, ptime: 60, dtx: true, fec: false, maxPlaybackRate: 16_000, video: nil)
        case .dataSaver:
            LabKnobs(opusBitrate: 15_000, ptime: 60, dtx: true, fec: true, maxPlaybackRate: 16_000, video: nil)
        case .balanced:
            LabKnobs(opusBitrate: 24_000, ptime: 20, dtx: true, fec: true, maxPlaybackRate: 48_000, video: .p270_15)
        case .high:
            LabKnobs(opusBitrate: 40_000, ptime: 20, dtx: true, fec: true, maxPlaybackRate: 48_000, video: .p360_20)
        }
    }
}
