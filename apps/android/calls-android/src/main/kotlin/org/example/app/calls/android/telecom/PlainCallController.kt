package org.example.app.calls.android.telecom

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import org.example.app.calls.android.CallController
import org.example.app.calls.android.CallInfo
import org.example.app.calls.android.CallNotifications
import org.example.app.calls.android.CallService
import org.example.app.core.calls.CallEndReason

/** Calls without Telecom: only the foreground [CallService] and its notification. */
class PlainCallController(private val context: Context) : CallController {
    private val calls = ConcurrentHashMap<String, CallInfo>()

    override fun reportIncoming(call: CallInfo) {
        calls[call.callId] = call
        CallService.ring(context, call)
    }

    override fun reportOutgoing(call: CallInfo) {
        calls[call.callId] = call
        CallService.answered(context, call, sendVideo = call.video)
    }

    override fun reportAnswered(callId: String, video: Boolean) {
        calls[callId]?.let { CallService.answered(context, it, sendVideo = video) }
    }

    override fun reportActive(callId: String) = Unit

    override fun reportEnded(callId: String, reason: CallEndReason) {
        calls.remove(callId)
        CallService.stop(context)
    }

    override fun reportMissed(call: CallInfo) = CallNotifications.missed(context, call)
}
