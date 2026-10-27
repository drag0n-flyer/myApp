package org.example.app.contracts

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The order is the Swift raw-value order. */
enum class DataMode { UltraLow, DataSaver, Balanced, High }

@Serializable
data class IceCandidate(val sdp: String, val sdpMid: String? = null, val sdpMLineIndex: Int)

/** `ceiling` is a string on the wire, per docs/protocol/calls.md. */
@Serializable
data class CallSignalPayload(
    val kind: Kind,
    val callID: String,
    val video: Boolean? = null,
    val sdp: String? = null,
    val dtlsFingerprint: String? = null,
    val candidate: IceCandidate? = null,
    @Serializable(with = DataModeCeilingSerializer::class) val ceiling: DataMode? = null,
    val videoOn: Boolean? = null,
) {
    @Suppress("EnumEntryName")
    enum class Kind { ring, offer, answer, candidate, renegotiate, renegotiateAnswer, modeChange }
}

/** Writes and reads the camel-case name (`ultraLow`, `dataSaver`, `balanced`, `high`); rejects anything else. */
object DataModeCeilingSerializer : KSerializer<DataMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DataModeCeiling", PrimitiveKind.STRING)

    private val names = mapOf(
        "ultraLow" to DataMode.UltraLow,
        "dataSaver" to DataMode.DataSaver,
        "balanced" to DataMode.Balanced,
        "high" to DataMode.High,
    )

    override fun serialize(encoder: Encoder, value: DataMode) {
        encoder.encodeString(names.entries.first { it.value == value }.key)
    }

    override fun deserialize(decoder: Decoder): DataMode {
        val name = decoder.decodeString()
        return names[name] ?: throw SerializationException("unknown ceiling: $name")
    }
}

sealed class CallSealException : Exception() {
    object WrongSender : CallSealException()
    object Undecryptable : CallSealException()
}

/**
 * A call payload that [CallSignalSealer.open] accepted. [senderDeviceID] is the device part of the sender's MLS
 * credential identity (`<accountID>/<deviceID>`, docs/protocol/mls-profile.md#identity). A production sealer
 * never returns a null device: an identity without a `/` is malformed and makes it throw
 * [CallSealException.WrongSender]. Null is for test fakes only; a machine then trusts the server's `fromDeviceID`.
 */
class OpenedCallSignal(val payload: CallSignalPayload, val senderDeviceID: String? = null)

interface CallSignalSealer {
    /** [to] is a contact account ID. */
    suspend fun seal(payload: CallSignalPayload, to: String): ByteArray

    /**
     * Opens a call frame sent by the account [from]. It must return application messages only: a commit, a
     * proposal or a Welcome that arrives on a call channel must be rejected (throw [CallSealException]) before
     * any group state changes. The sender account and device come from the MLS credential, never from
     * server-stated fields.
     *
     * A production sealer never returns a null [OpenedCallSignal.senderDeviceID]. If the credential identity has
     * no `/`, it throws [CallSealException.WrongSender]. Null is for test fakes only.
     */
    suspend fun open(data: ByteArray, from: String): OpenedCallSignal
}
