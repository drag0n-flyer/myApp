package org.example.app.calls.android.telecom

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import java.util.concurrent.ConcurrentHashMap
import org.example.app.calls.android.CallController
import org.example.app.calls.android.CallExtras
import org.example.app.calls.android.CallInfo
import org.example.app.calls.android.CallNotifications
import org.example.app.calls.android.CallService
import org.example.app.calls.android.Ringer
import org.example.app.calls.android.RingingCall
import org.example.app.calls.android.SystemRinger
import org.example.app.core.calls.CallEndReason

/**
 * Calls through Telecom as a self-managed phone account: the system knows about the call (audio focus, Bluetooth,
 * other calls), and the app shows the UI ([AppConnection.onShowIncomingCallUi] posts the ringing call and rings;
 * [CallService] starts only once the call is answered or placed).
 * [reportIncoming] and [reportOutgoing] throw when Telecom refuses; [FallbackCallController] then uses
 * [PlainCallController].
 */
class TelecomCallController(private val context: Context, ringer: Ringer = SystemRinger(context)) : CallController {
    private val telecom: TelecomManager?
        get() = context.getSystemService(TelecomManager::class.java)
    private val calls = ConcurrentHashMap<String, CallInfo>()
    private val ringing = RingingCall(context, ringer)

    init {
        AppConnections.showIncomingUi = { ringing.show(it) }
    }

    override fun reportIncoming(call: CallInfo) {
        val manager = telecom ?: throw IllegalStateException("no telecom")
        val handle = PhoneAccounts.register(context)
        if (!manager.isIncomingCallPermitted(handle)) throw IllegalStateException("incoming call not permitted")
        calls[call.callId] = call
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccounts.SCHEME, call.contact.id, null))
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, CallExtras.bundle(call))
            CallExtras.write(this, call)
        }
        manager.addNewIncomingCall(handle, extras)
    }

    override fun reportOutgoing(call: CallInfo) {
        val manager = telecom ?: throw IllegalStateException("no telecom")
        val handle = PhoneAccounts.register(context)
        if (!manager.isOutgoingCallPermitted(handle)) throw IllegalStateException("outgoing call not permitted")
        // The call service first: if the system refuses it, nothing has been placed with Telecom yet.
        CallService.answered(context, call, sendVideo = call.video)
        calls[call.callId] = call
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, CallExtras.bundle(call))
        }
        // Self-managed calls need MANAGE_OWN_CALLS (declared), not CALL_PHONE.
        @Suppress("MissingPermission")
        manager.placeCall(Uri.fromParts(PhoneAccounts.SCHEME, call.contact.id, null), extras)
    }

    /** Throws when the call service cannot start (ForegroundServiceStartNotAllowedException): the engine fails the call. */
    override fun reportAnswered(callId: String, video: Boolean) {
        ringing.dismiss()
        calls[callId]?.let { CallService.answered(context, it, sendVideo = video) }
        AppConnections[callId]?.setActive()
    }

    override fun reportActive(callId: String) {
        AppConnections[callId]?.setActive()
    }

    override fun reportEnded(callId: String, reason: CallEndReason) {
        // Before the connection exists too: AppConnectionService then fails it.
        AppConnections.markEnded(callId)
        ringing.dismiss()
        calls.remove(callId)
        AppConnections.remove(callId)?.end(reason)
        CallService.stop(context)
    }

    override fun reportMissed(call: CallInfo) = CallNotifications.missed(context, call)
}

/**
 * Telecom first, the plain controller when Telecom refuses a call, at once (an exception) or later
 * ([telecomFailed], from `onCreate…ConnectionFailed`). A call stays with the controller that took it.
 */
class FallbackCallController(private val telecom: CallController, private val plain: CallController) : CallController {
    private class Placed(val call: CallInfo, val incoming: Boolean)

    private val placed = ConcurrentHashMap<String, Placed>()
    private val onPlain = ConcurrentHashMap.newKeySet<String>()

    private fun of(callId: String) = if (callId in onPlain) plain else telecom

    override fun reportIncoming(call: CallInfo) = place(call, incoming = true)

    override fun reportOutgoing(call: CallInfo) = place(call, incoming = false)

    private fun place(call: CallInfo, incoming: Boolean) {
        placed[call.callId] = Placed(call, incoming)
        try {
            if (incoming) telecom.reportIncoming(call) else telecom.reportOutgoing(call)
        } catch (_: Exception) {
            onPlain += call.callId
            if (incoming) plain.reportIncoming(call) else plain.reportOutgoing(call)
        }
    }

    /** Telecom refused a call after accepting the request: the plain controller shows it. */
    fun telecomFailed(callId: String) {
        val p = placed[callId] ?: return
        if (!onPlain.add(callId)) return
        if (p.incoming) plain.reportIncoming(p.call) else plain.reportOutgoing(p.call)
    }

    override fun reportAnswered(callId: String, video: Boolean) = of(callId).reportAnswered(callId, video)

    override fun reportActive(callId: String) = of(callId).reportActive(callId)

    override fun reportEnded(callId: String, reason: CallEndReason) {
        val controller = of(callId)
        placed.remove(callId)
        onPlain.remove(callId)
        controller.reportEnded(callId, reason)
    }

    override fun reportMissed(call: CallInfo) = plain.reportMissed(call)
}
