public enum MediaKind: Sendable {
    case photo, video, file, voice
}

public enum DownloadScope: String, CaseIterable, Codable, Sendable {
    case all, photosOnly, none

    public var title: String {
        switch self {
        case .all: "All"
        case .photosOnly: "Photos only"
        case .none: "None"
        }
    }
}

public struct AutoDownloadSettings: Equatable, Codable, Sendable {
    public var onWiFi: DownloadScope = .all
    public var onMobile: DownloadScope = .none
    public var voiceAlways = true

    public init() {}
}

public enum AutoDownloadPolicy {
    public static func shouldDownload(_ kind: MediaKind, network: NetworkType, settings: AutoDownloadSettings) -> Bool {
        let scope: DownloadScope
        switch network {
        case .offline: return false
        case .wifi: scope = settings.onWiFi
        case .mobile: scope = settings.onMobile
        }
        if kind == .voice && settings.voiceAlways { return true }
        switch scope {
        case .all: return true
        case .photosOnly: return kind == .photo
        case .none: return false
        }
    }
}
