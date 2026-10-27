/// The user's call data settings.
public struct CallSettings: Equatable, Codable, Sendable {
    public var callDataMode: ModePreference = .auto
    public var mobileDataLimit: DataMode = .dataSaver
    public var videoOffOnMobile = true

    public init() {}
}
