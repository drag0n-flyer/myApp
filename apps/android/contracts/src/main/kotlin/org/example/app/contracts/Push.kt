package org.example.app.contracts

sealed interface NotificationOutcome {
    object Nothing : NotificationOutcome

    /** Shows "[App]", "New message". */
    object Generic : NotificationOutcome
    class Message(val chatId: String, val senderName: String, val preview: String) : NotificationOutcome

    /** Shows "Message request". */
    class Request(val chatId: String) : NotificationOutcome
    class NewOwnDevice(val deviceName: String?) : NotificationOutcome

    /** The push payload was empty. */
    object FetchInbox : NotificationOutcome
}

sealed interface RingOutcome {
    class Ring(val callId: String, val fromAccountId: String, val callerName: String, val video: Boolean) : RingOutcome

    /** A stranger called: no ring. */
    class SilentMissed(val callId: String, val fromAccountId: String) : RingOutcome
    object Drop : RingOutcome
}

interface PushPayloadHandler {
    /** Implemented by `:messaging` (D7). */
    suspend fun onMessagePush(padded: ByteArray): NotificationOutcome

    /** Implemented by `:messaging` (D7). */
    suspend fun onCallPush(padded: ByteArray): RingOutcome
}
