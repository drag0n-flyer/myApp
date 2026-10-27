public enum SettingScope: Sendable {
    /// Synced end-to-end encrypted between the user's linked devices.
    case account
    /// Stays on this device.
    case device
}

public enum SettingKey: CaseIterable, Sendable {
    case chatFolders, contacts, blockedList, privacy, callDataMode, mobileDataLimit, readState, disappearingMessages
    case autoDownload, storage, noiseSuppression, notificationSounds, appearance

    public var scope: SettingScope {
        switch self {
        case .chatFolders, .contacts, .blockedList, .privacy, .callDataMode, .mobileDataLimit, .readState, .disappearingMessages:
            .account
        case .autoDownload, .storage, .noiseSuppression, .notificationSounds, .appearance:
            .device
        }
    }
}
