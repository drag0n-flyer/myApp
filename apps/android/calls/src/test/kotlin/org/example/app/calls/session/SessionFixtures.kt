package org.example.app.calls.session

import kotlinx.serialization.json.Json
import org.example.app.calls.policy.AudioProfile
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.CallSignalSealer
import org.example.app.contracts.DataMode
import org.example.app.contracts.IceCandidate
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Contact
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import org.example.app.core.settings.UserSettings
import org.example.app.core.calls.DataMode as CoreMode

internal val bob = Contact(id = "bob", displayName = "Bob", username = "bob")
internal val server = ServerPolicy(
    defaultPreference = ModePreference.Auto, maximum = CoreMode.High,
    defaultIPHiding = IPHiding.NonContacts, requiresInvite = true, host = "h",
)

internal fun ctx(isContact: Boolean = true, network: NetworkType = NetworkType.Wifi) =
    CallContext(server, UserSettings(ipHiding = IPHiding.NonContacts), network, isContact)

internal fun sdp(f: String) = "v=0\r\na=fingerprint:$f\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
internal fun cand(n: Int) = IceCandidate(sdp = "candidate:$n", sdpMid = "0", sdpMLineIndex = 0)
internal fun candPayload(n: Int, callID: String = "c1") =
    CallSignalPayload(CallSignalPayload.Kind.candidate, callID, candidate = cand(n))
internal fun profile(m: DataMode) = AudioProfile.forMode(m)

internal class ManualClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun advance(seconds: Double) { now += (seconds * 1000).toLong() }
}

/**
 * Plain-text stand-in for the MLS sealer: "<senderAccount>|<json>". Opening checks the sender account
 * like the real one; anything that does not parse is undecryptable.
 */
internal class PlainSealer(private val me: String = "me") : CallSignalSealer {
    private val json = Json { explicitNulls = false }
    override suspend fun seal(payload: CallSignalPayload, to: String): ByteArray =
        "$me|${json.encodeToString(CallSignalPayload.serializer(), payload)}".encodeToByteArray()

    override suspend fun open(data: ByteArray, from: String): CallSignalPayload {
        val text = data.decodeToString()
        val sender = text.substringBefore('|', missingDelimiterValue = "")
        val body = text.substringAfter('|', missingDelimiterValue = "")
        val p = runCatching { json.decodeFromString(CallSignalPayload.serializer(), body) }
            .getOrElse { throw CallSealException.Undecryptable }
        if (sender != from) throw CallSealException.WrongSender
        return p
    }

    /** Sealed as if sent by `account`. */
    fun from(account: String, p: CallSignalPayload): ByteArray =
        "$account|${json.encodeToString(CallSignalPayload.serializer(), p)}".encodeToByteArray()
}

internal fun machine(context: CallContext = ctx(), clock: Clock = ManualClock()) =
    CallMachine(context, PlainSealer(), clock)

internal fun List<CallEffect>.published() = filterIsInstance<CallEffect.Publish>().lastOrNull()?.state
internal fun List<CallEffect>.hasSetRemote() = any { it is CallEffect.SetRemote }
internal fun List<CallEffect>.hasAcceptOffer() = any { it is CallEffect.AcceptOffer }
internal fun List<CallEffect>.hasAcceptAnswer() = any { it is CallEffect.AcceptAnswer }
internal fun List<CallEffect>.hasHistory() = any { it is CallEffect.RecordHistory }
internal fun List<CallEffect>.sendsFrame(type: String) = any { it is CallEffect.SendFrame && it.type == type }
internal fun List<CallEffect>.hasRestartIce() = any { it is CallEffect.RestartIce }
internal fun List<CallEffect>.hasCreateOffer() = any { it is CallEffect.CreateOffer }
internal fun List<CallEffect>.signal(kind: CallSignalPayload.Kind): CallSignalPayload? =
    filterIsInstance<CallEffect.SendFrame>().firstOrNull { it.type == CallFrame.SIGNAL && it.payload?.kind == kind }?.payload
internal fun List<CallEffect>.videoEffects() = filterIsInstance<CallEffect.SetVideo>()

internal fun signalFrame(p: CallSignalPayload) = CallEffect.SendFrame(CallFrame.SIGNAL, p)
internal val hangupFrame = CallEffect.SendFrame(CallFrame.HANGUP)
