package org.example.app.calls.android.telecom

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import org.example.app.calls.android.CallController
import org.example.app.calls.android.CallInfo
import org.example.app.calls.android.CallNotifications
import org.example.app.calls.android.CallService
import org.example.app.calls.android.Ringer
import org.example.app.calls.android.RingingCall
import org.example.app.calls.android.SystemRinger
import org.example.app.core.calls.CallEndReason

/**
 * Calls without Telecom: the ringing notification and [Ringer] while it rings, then the foreground [CallService]
 * once it is answered or placed (never while ringing: a push-woken app may not be allowed to start one).
 */
class PlainCallController(private val context: Context, ringer: Ringer = SystemRinger(context)) : CallController {
    private val calls = ConcurrentHashMap<String, CallInfo>()
    private val ringing = RingingCall(context, ringer)

    override fun reportIncoming(call: CallInfo) {
        calls[call.callId] = call
        ringing.show(call)
    }

    override fun reportOutgoing(call: CallInfo) {
        calls[call.callId] = call
        CallService.answered(context, call, sendVideo = call.video)
    }

    /** Throws when the call service cannot start (ForegroundServiceStartNotAllowedException): the engine fails the call. */
    override fun reportAnswered(callId: String, video: Boolean) {
        ringing.dismiss()
        calls[callId]?.let { CallService.answered(context, it, sendVideo = video) }
    }

    override fun reportActive(callId: String) = Unit

    override fun reportEnded(callId: String, reason: CallEndReason) {
        ringing.dismiss()
        calls.remove(callId)
        CallService.stop(context)
    }

    override fun reportMissed(call: CallInfo) = CallNotifications.missed(context, call)
}
