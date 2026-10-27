package org.example.app.push

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.example.app.contracts.NotificationOutcome
import org.example.app.contracts.PushPadding
import org.example.app.contracts.PushPayloadHandler
import org.example.app.contracts.RingOutcome
import org.example.app.mls.MlsGate

/** What a push can cause, apart from ringing. Real implementation: [AndroidPushEffects]. */
interface PushEffects {
    fun show(outcome: NotificationOutcome)

    /** Starts the expedited inbox fetch ([InboxFetchWorker]). */
    fun enqueueInboxFetch()

    suspend fun recordMissedCall(callId: String, fromAccountId: String)

    /** Bookkeeping for [MissedPushMonitor]. */
    fun pushHandled(highPriority: Boolean, showedSomething: Boolean)
}

/**
 * Turns an FCM data message or a UnifiedPush message into a notification or a ring. Short work only:
 * nothing here runs longer than [budgetMs]; whatever is not done by then goes to [InboxFetchWorker].
 * Decryption runs under [MlsGate].
 */
class PushDispatcher(
    private val handler: PushPayloadHandler,
    private val ring: IncomingCallSink,
    private val effects: PushEffects,
    private val budgetMs: Long = BUDGET_MS,
) {
    companion object {
        const val BUDGET_MS = 8_000L
        const val KIND_MESSAGE: Byte = 0x01
        const val KIND_CALL: Byte = 0x02
    }

    private enum class Kind { Message, Call }

    /** FCM: `data["k"]` is `message` or `call`; `data["d"]` is standard base64 of the padded payload. */
    suspend fun onFcm(data: Map<String, String>, highPriority: Boolean) {
        val kind = when (data["k"]) {
            "message" -> Kind.Message
            "call" -> Kind.Call
            else -> return
        }
        val padded = try {
            Base64.decode(data["d"] ?: "", Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
        dispatch(kind, padded, highPriority)
    }

    /** UnifiedPush: one kind byte (`0x01` message, `0x02` call), then the padded payload. Always high urgency. */
    suspend fun onUnifiedPush(content: ByteArray) {
        if (content.isEmpty()) return
        val kind = when (content[0]) {
            KIND_MESSAGE -> Kind.Message
            KIND_CALL -> Kind.Call
            else -> return
        }
        dispatch(kind, content.copyOfRange(1, content.size), highPriority = true)
    }

    private suspend fun dispatch(kind: Kind, padded: ByteArray?, highPriority: Boolean) {
        when (kind) {
            Kind.Message -> onMessage(padded, highPriority)
            Kind.Call -> onCall(padded, highPriority)
        }
    }

    private suspend fun onMessage(padded: ByteArray?, highPriority: Boolean) {
        val payload = padded?.let { PushPadding.unpad(it) }
        if (padded == null || payload == null) {
            show(NotificationOutcome.Generic, highPriority)
            return
        }
        if (payload.isEmpty()) {
            effects.enqueueInboxFetch()
            effects.pushHandled(highPriority, showedSomething = true) // the fetch decides; not a quiet push
            return
        }
        val outcome = try {
            withTimeoutOrNull(budgetMs) { MlsGate.withLock { handler.onMessagePush(padded) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NotificationOutcome.Generic
        }
        when (outcome) {
            null, NotificationOutcome.FetchInbox -> {
                effects.enqueueInboxFetch()
                effects.pushHandled(highPriority, showedSomething = true)
            }
            NotificationOutcome.Nothing -> effects.pushHandled(highPriority, showedSomething = false)
            else -> show(outcome, highPriority)
        }
    }

    private fun show(outcome: NotificationOutcome, highPriority: Boolean) {
        effects.show(outcome)
        effects.pushHandled(highPriority, showedSomething = true)
    }

    private suspend fun onCall(padded: ByteArray?, highPriority: Boolean) {
        val payload = padded?.let { PushPadding.unpad(it) }
        if (padded == null || payload == null || payload.isEmpty()) {
            effects.pushHandled(highPriority, showedSomething = false)
            return
        }
        // A ring that cannot be opened is dropped silently: Android has no rule forcing a call UI.
        val outcome = try {
            withTimeoutOrNull(budgetMs) { MlsGate.withLock { handler.onCallPush(padded) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        when (outcome) {
            is RingOutcome.Ring -> {
                ring.ring(outcome)
                effects.pushHandled(highPriority, showedSomething = true)
            }
            is RingOutcome.SilentMissed -> {
                effects.recordMissedCall(outcome.callId, outcome.fromAccountId)
                effects.pushHandled(highPriority, showedSomething = false)
            }
            else -> effects.pushHandled(highPriority, showedSomething = false)
        }
    }
}
