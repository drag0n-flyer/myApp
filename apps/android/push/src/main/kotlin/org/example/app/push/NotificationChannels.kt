package org.example.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val MESSAGES = "messages"
    const val REQUESTS = "requests"
    const val CALLS = "calls"
    const val DEVICES = "devices"
    const val SERVICE = "service"

    /** Idempotent. minSdk is 29, so channels always exist. */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance)
        manager.createNotificationChannels(
            listOf(
                channel(MESSAGES, R.string.push_channel_messages, NotificationManager.IMPORTANCE_HIGH),
                channel(REQUESTS, R.string.push_channel_requests, NotificationManager.IMPORTANCE_DEFAULT),
                channel(CALLS, R.string.push_channel_calls, NotificationManager.IMPORTANCE_HIGH),
                channel(DEVICES, R.string.push_channel_devices, NotificationManager.IMPORTANCE_DEFAULT),
                channel(SERVICE, R.string.push_channel_service, NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }
}
