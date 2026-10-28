package org.example.app.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.example.app.core.settings.UserSettings

/** Holds the user's settings in memory until the synced store exists. */
class SettingsStore(initial: UserSettings = UserSettings()) {
    private val state = MutableStateFlow(initial)
    val settings: StateFlow<UserSettings> = state.asStateFlow()
    fun update(change: (UserSettings) -> UserSettings) = state.update(change)

    private val deliveryState = MutableStateFlow(NotificationDelivery.Google)
    /** Sample choice until the push settings are wired (the live push choice lives in :push). */
    val delivery: StateFlow<NotificationDelivery> = deliveryState.asStateFlow()
    fun setDelivery(value: NotificationDelivery) { deliveryState.value = value }
}

/** How push messages reach this phone. */
enum class NotificationDelivery { Google, UnifiedPush }
