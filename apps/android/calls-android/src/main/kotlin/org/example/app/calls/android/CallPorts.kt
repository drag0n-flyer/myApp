package org.example.app.calls.android

import org.example.app.core.calls.CallEndReason
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Contact

/** A call the phone shows. */
data class CallInfo(val callId: String, val contact: Contact, val video: Boolean)

/**
 * Shows calls on the phone: Telecom ([org.example.app.calls.android.telecom.TelecomCallController]) or, where
 * Telecom refuses, a plain foreground service and notification
 * ([org.example.app.calls.android.telecom.PlainCallController]). Main thread or any thread; never blocks.
 */
interface CallController {
    /** A ringing call. Throws when the platform refuses it. */
    fun reportIncoming(call: CallInfo)
    fun reportOutgoing(call: CallInfo)

    /** The user answered (callee) or the call is being set up (caller): the microphone, and the camera for video, are in use. */
    fun reportAnswered(callId: String, video: Boolean)
    fun reportActive(callId: String)
    fun reportEnded(callId: String, reason: CallEndReason)

    /** A call that ended before this device could ring: a "Missed call" notice. */
    fun reportMissed(call: CallInfo)
}

/** Who an account is to this device. Only a contact that is not blocked rings. */
class CallPeer(val contact: Contact, val isContact: Boolean, val isBlocked: Boolean)

fun interface CallPeers {
    /** Null for an account this device knows nothing about. */
    fun peer(accountId: String): CallPeer?
}

/** Writes the call into the chat (`ChatStore.recordCall`). */
fun interface CallHistory {
    fun record(summary: CallSummary, contactId: String, outgoing: Boolean)
}
