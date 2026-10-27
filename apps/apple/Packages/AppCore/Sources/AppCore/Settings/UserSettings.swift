/// The user's call data settings.
public struct CallSettings: Equatable, Codable, Sendable {
    public var callDataMode: ModePreference = .auto
    public var mobileDataLimit: DataMode = .dataSaver
    public var videoOffOnMobile = true

    public init() {}
}

public enum NoiseSuppression: String, CaseIterable, Codable, Sendable {
    case standard, enhanced, off
}

public struct UserSettings: Equatable, Codable, Sendable {
    public var call: CallSettings
    public var autoDownload: AutoDownloadSettings
    public var ipHiding: IPHiding
    public var findByUsername = true
    public var findByEmail = false
    public var readReceipts = true
    public var disappearingMessages: Double? = nil
    public var noiseSuppression: NoiseSuppression = .standard

    public init(call: CallSettings = .init(), autoDownload: AutoDownloadSettings = .init(), ipHiding: IPHiding = .nonContacts) {
        self.call = call
        self.autoDownload = autoDownload
        self.ipHiding = ipHiding
    }

    /// A new user's settings, taking the server administrator's defaults.
    public static func initial(from server: ServerPolicy) -> UserSettings {
        var call = CallSettings()
        call.callDataMode = server.defaultPreference
        return UserSettings(call: call, ipHiding: server.defaultIPHiding)
    }
}
