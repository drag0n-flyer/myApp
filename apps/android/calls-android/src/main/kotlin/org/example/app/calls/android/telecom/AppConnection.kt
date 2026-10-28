package org.example.app.calls.android.telecom

import android.content.Context
import android.net.Uri
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import java.util.concurrent.ConcurrentHashMap
import org.example.app.calls.android.CallExtras
import org.example.app.calls.android.CallInfo
import org.example.app.calls.android.CallsRuntime
import org.example.app.core.calls.CallEndReason

/**
 * One call in Telecom. Telecom's requests (answer from a headset or watch, reject, disconnect) go to the engine;
 * the engine's results come back through [TelecomCallController]. Being self-managed, it shows its own UI.
 */
class AppConnection(private val context: Context, val call: CallInfo) : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_MUTE
        audioModeIsVoip = true
        setAddress(Uri.fromParts(PhoneAccounts.SCHEME, call.contact.id, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(CallExtras.displayName(context, call), TelecomManager.PRESENTATION_ALLOWED)
        videoState = if (call.video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
    }

    /** Self-managed: the app shows the ringing call and rings, with no foreground service until it is answered. */
    override fun onShowIncomingCallUi() {
        AppConnections.showIncomingUi?.invoke(call)
    }

    override fun onAnswer() {
        CallsRuntime.engine?.answer(call.video)
    }

    override fun onAnswer(videoState: Int) {
        CallsRuntime.engine?.answer(call.video && VideoProfile.isVideo(videoState))
    }

    override fun onReject() {
        CallsRuntime.engine?.decline()
    }

    override fun onDisconnect() {
        CallsRuntime.engine?.hangUp()
    }

    override fun onAbort() {
        CallsRuntime.engine?.hangUp()
    }

    /** The engine ended the call. */
    fun end(reason: CallEndReason) {
        setDisconnected(DisconnectCause(cause(reason)))
        destroy()
    }

    private fun cause(reason: CallEndReason): Int = when (reason) {
        CallEndReason.HungUp -> DisconnectCause.LOCAL
        CallEndReason.Declined -> DisconnectCause.REJECTED
        CallEndReason.Missed -> DisconnectCause.MISSED
        CallEndReason.AnsweredElsewhere -> DisconnectCause.ANSWERED_ELSEWHERE
        CallEndReason.ConnectionLost, CallEndReason.Failed -> DisconnectCause.ERROR
    }
}

/**
 * The live connections by call ID, and the calls the engine already ended. `addNewIncomingCall` and `placeCall`
 * are asynchronous: a call can end before Telecom creates its connection, which must then fail, not ring or dial
 * forever.
 */
internal object AppConnections {
    private const val MAX_ENDED = 64
    private val byCall = ConcurrentHashMap<String, AppConnection>()
    private val ended = LinkedHashSet<String>()

    /** Set by [TelecomCallController]: shows the ringing call. */
    @Volatile var showIncomingUi: ((CallInfo) -> Unit)? = null

    fun markEnded(callId: String) = synchronized(ended) {
        ended += callId
        if (ended.size > MAX_ENDED) ended.remove(ended.first())
    }

    fun isEnded(callId: String): Boolean = synchronized(ended) { callId in ended }

    fun put(connection: AppConnection) {
        byCall[connection.call.callId] = connection
    }

    operator fun get(callId: String): AppConnection? = byCall[callId]

    fun remove(callId: String): AppConnection? = byCall.remove(callId)
}
