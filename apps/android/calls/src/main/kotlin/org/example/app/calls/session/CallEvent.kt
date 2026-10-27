package org.example.app.calls.session

import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.MediaStats
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.IceCandidate
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Contact
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings

/** Milliseconds since the epoch. Injected so the machine is deterministic in tests. */
fun interface Clock {
    fun nowMs(): Long
}

data class CallContext(
    val server: ServerPolicy,
    val settings: UserSettings,
    val network: NetworkType,
    /** False for message-request senders and unknown contacts, so relay privacy treats them as strangers. */
    val isContact: Boolean,
)

/** Call frames from the server (server design §7.3). `payload` is the sealed MLS message. */
sealed interface CallServerEvent {
    val callID: String

    class Ring(
        override val callID: String, val fromAccountID: String, val fromDeviceID: String,
        val video: Boolean, val payload: ByteArray,
    ) : CallServerEvent

    class Answered(override val callID: String, val deviceID: String, val payload: ByteArray = ByteArray(0)) : CallServerEvent

    /** Server-sent signals always carry their sender device. */
    class Signal(override val callID: String, val fromDeviceID: String, val payload: ByteArray) : CallServerEvent

    data class Ended(override val callID: String, val reason: String) : CallServerEvent
}

/** The answer to `call.status`, used by a device woken by a call push. */
sealed interface CallStatus {
    data object Ringing : CallStatus
    data object Answered : CallStatus
    data class Ended(val reason: String) : CallStatus
    data object Unknown : CallStatus
}

sealed interface MediaEvent {
    data class LocalCandidate(val candidate: IceCandidate) : MediaEvent
    data class IceStateChanged(val state: IceState) : MediaEvent
    data class RemoteVideo(val on: Boolean) : MediaEvent
}

/** Inputs of [CallMachine]. Mirrors the iPhone's `CallInput`. */
sealed interface CallEvent {
    data class Start(val contact: Contact, val video: Boolean, val callID: String) : CallEvent

    /** A ring opened by the engine. `fromDeviceID` is empty when a push woke the device (no WebSocket ring seen). */
    data class Incoming(val callID: String, val contact: Contact, val fromDeviceID: String, val ring: CallSignalPayload) : CallEvent

    data class Answer(val video: Boolean) : CallEvent
    data object Decline : CallEvent
    data object Hangup : CallEvent
    data class SetPreference(val preference: ModePreference) : CallEvent
    data class SetVideo(val on: Boolean) : CallEvent
    data class SetMuted(val on: Boolean) : CallEvent
    data class SetSpeaker(val on: Boolean) : CallEvent

    /** `call.answered` and `call.ended`. Payloads reach the machine as [Remote] after they are opened. */
    data class Server(val event: CallServerEvent) : CallEvent

    /** A `call.status` result for this call. */
    data class Status(val callID: String, val status: CallStatus) : CallEvent

    /** An opened payload from the expected contact. `fromDeviceID` is the server-reported sender device. */
    data class Remote(val payload: CallSignalPayload, val fromDeviceID: String) : CallEvent

    /** A payload from `fromDeviceID` that failed to open. Only the answering device can end the call this way. */
    data class RemoteRejected(val error: CallSealException, val fromDeviceID: String) : CallEvent

    data class Media(val event: MediaEvent) : CallEvent
    data class LocalOffer(val sdp: String) : CallEvent
    data class LocalAnswer(val sdp: String) : CallEvent
    data class Stats(val stats: MediaStats) : CallEvent
    data object PathChanged : CallEvent

    /** Media, ICE or sealing failed on this device. Ends as failed; a ringing callee does not touch the server. */
    data object LocalFailure : CallEvent

    data object Tick : CallEvent
    data class ContextChanged(val context: CallContext) : CallEvent
}
