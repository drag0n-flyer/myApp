package org.example.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * FCM entry point, in the app process. [onMessageReceived] gets about 10 seconds: the dispatcher keeps to 8
 * and hands the rest to the expedited [InboxFetchWorker]. No foreground service.
 */
class AppFirebaseService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val dispatcher = PushRuntime.dispatcher ?: return
        // The priority the server sent, not the delivered one: FCM may lower the delivered priority of an app
        // whose high-priority pushes show nothing, and that must not hide the count.
        val high = message.originalPriority == RemoteMessage.PRIORITY_HIGH
        runBlocking(Dispatchers.Default) { dispatcher.onFcm(message.data, high) }
    }

    // The Firebase base class marks this member deprecated in its Kotlin signature, but it is still the only
    // callback for a new token.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        val sink = PushRuntime.registrationSink ?: return
        runBlocking(Dispatchers.Default) {
            kotlinx.coroutines.withTimeoutOrNull(PushDispatcher.BUDGET_MS) {
                try {
                    sink.onFcmToken(token)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The app registers again at its next start.
                }
            }
        }
    }
}
