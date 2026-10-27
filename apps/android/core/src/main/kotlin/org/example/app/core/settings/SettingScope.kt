package org.example.app.core.settings

enum class SettingScope {
    /** Synced end-to-end encrypted between the user's linked devices. */
    Account,

    /** Stays on this device. */
    Device,
}

enum class SettingKey(val scope: SettingScope) {
    ChatFolders(SettingScope.Account),
    Contacts(SettingScope.Account),
    BlockedList(SettingScope.Account),
    Privacy(SettingScope.Account),
    CallDataMode(SettingScope.Account),
    MobileDataLimit(SettingScope.Account),
    ReadState(SettingScope.Account),
    DisappearingMessages(SettingScope.Account),
    AutoDownload(SettingScope.Device),
    Storage(SettingScope.Device),
    NoiseSuppression(SettingScope.Device),
    NotificationSounds(SettingScope.Device),
    Appearance(SettingScope.Device),
}
