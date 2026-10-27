package org.example.app.push

import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush entry point, in the app process. Work runs under `goAsync` and ends within the dispatcher's
 * 8 second budget. Never log an endpoint or a payload.
 */
class AppUnifiedPushReceiver : MessagingReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        // Content the connector could not authenticate and decrypt is not ours: ignore it.
        if (!message.decrypted) return
        val dispatcher = PushRuntime.dispatcher ?: return
        async { dispatcher.onUnifiedPush(message.content) }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val sink = PushRuntime.registrationSink ?: return
        val registration = UnifiedPushSetup.toRegistration(endpoint) ?: return
        async { sink.onRegistration(registration) }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) = Unit

    override fun onUnregistered(context: Context, instance: String) = Unit

    private fun async(block: suspend () -> Unit) {
        // Connector 3.3.5 (checked in its bytecode) never calls goAsync() and runs onMessage synchronously inside
        // onReceive, so this is the only goAsync() and keeps the process alive until finish().
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(PushDispatcher.BUDGET_MS) {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Dropped; the inbox fetch on the next start catches up.
                    }
                }
            } finally {
                pending?.finish()
            }
        }
    }
}
