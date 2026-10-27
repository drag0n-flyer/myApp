/// One row of the in-call "Data for this call" sheet.
public struct DataSheetOption: Equatable, Sendable {
    public let preference: ModePreference
    public let title: String
    public let subtitle: String?
    public let estimate: String?
    public let note: String?
    public let isEnabled: Bool
    public let isSelected: Bool
}

/// Decides which data mode a call may use.
///
/// Order of limits: the server maximum always applies; a fixed in-call choice
/// replaces the user's default and lifts the mobile-data limit for that call;
/// otherwise the mobile-data limit applies on mobile data; the network decides last.
public enum DataModePolicy {
    public static func ceiling(server: ServerPolicy, settings: CallSettings, network: NetworkType, inCall: ModePreference?) -> DataMode {
        let preference = inCall ?? settings.callDataMode
        var cap: DataMode
        switch preference {
        case .auto: cap = .high
        case .fixed(let mode): cap = mode
        }
        let liftsMobileLimit: Bool
        if case .fixed = inCall { liftsMobileLimit = true } else { liftsMobileLimit = false }
        if network == .mobile && !liftsMobileLimit {
            cap = min(cap, settings.mobileDataLimit)
        }
        return min(cap, server.maximum)
    }

    public static func effective(server: ServerPolicy, settings: CallSettings, network: NetworkType, inCall: ModePreference?, sustainable: DataMode) -> DataMode {
        min(ceiling(server: server, settings: settings, network: network, inCall: inCall), sustainable)
    }

    public static func sheetOptions(server: ServerPolicy, settings: CallSettings, network: NetworkType, inCall: ModePreference?) -> [DataSheetOption] {
        let selected = inCall ?? settings.callDataMode
        let auto = DataSheetOption(preference: .auto, title: "Auto", subtitle: "Best your network allows",
                                   estimate: nil, note: nil, isEnabled: true, isSelected: selected == .auto)
        let modes = DataMode.allCases.map { mode -> DataSheetOption in
            var note: String?
            var enabled = true
            if mode > server.maximum {
                note = "Limited by your server"
                enabled = false
            } else if network == .mobile && mode > settings.mobileDataLimit {
                note = "Above your mobile data limit"
            }
            return DataSheetOption(preference: .fixed(mode), title: mode.title, subtitle: mode.subtitle,
                                   estimate: DataFormat.estimate(mode), note: note, isEnabled: enabled,
                                   isSelected: selected == .fixed(mode))
        }
        return [auto] + modes
    }

    /// The mode a new call would start with on the current network, for the chat list strip.
    public static func stripLabel(server: ServerPolicy, settings: CallSettings, network: NetworkType) -> String {
        if settings.callDataMode == .auto && network != .mobile {
            return "Auto"
        }
        return ceiling(server: server, settings: settings, network: network, inCall: nil).title
    }
}
