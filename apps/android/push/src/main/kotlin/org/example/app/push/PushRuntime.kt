package org.example.app.push

import org.example.app.contracts.NotificationOutcome
import org.example.app.contracts.RingOutcome

/** Opens a ring for a contact's call. Implemented by `:calls-android` (`LiveCallEngine`, E6). */
interface IncomingCallSink {
    fun ring(outcome: RingOutcome.Ring)
}

/** Records a missed call from someone who is not a contact (no ring). Implemented by `:messaging`. */
interface MissedCallRecorder {
    suspend fun recordMissedCall(callId: String, fromAccountId: String)
}

/** Fetches the inbox and returns what to show for each new item (the D7 processor's `fetchOutcomes`). It must NOT run under `MlsGate`: the inbox processor takes the non-reentrant lock for each decrypt. */
interface InboxFetcher {
    suspend fun fetch(): List<NotificationOutcome>
}

/** Receives a new transport credential from a receiver. Implemented by the app with [PushRegistrar]. */
interface RegistrationSink {
    suspend fun onRegistration(registration: PushRegistration)

    suspend fun onFcmToken(token: String)
}

/**
 * The app wires these in `Application.onCreate`. Services and receivers are created by the system with no
 * constructor arguments, so they reach the app's objects here. All are null until the app sets them, and the
 * push entry points do nothing while they are.
 */
object PushRuntime {
    @Volatile var dispatcher: PushDispatcher? = null

    @Volatile var inboxFetcher: InboxFetcher? = null

    @Volatile var registrationSink: RegistrationSink? = null

    /** For N6: call it when app lock is turned on. Notifications already showing lose their sender, text and Reply. */
    fun hideShownContent(context: android.content.Context) = MessageNotifier(context.applicationContext).hideShownContent()

    /** What the notification buttons do. The app sets it in `PushWiring`; null while signed out. */
    @Volatile var notificationActions: NotificationActionSink? = null

    /** The chats whose notifications are quiet. The app wires this in `PushWiring`; empty until then. */
    @Volatile var mutedChatIds: () -> Set<String> = { emptySet() }

    /** True while app lock is on: notifications then show no sender or text and offer no Reply. */
    @Volatile var appLockOn: () -> Boolean = { false }
}
