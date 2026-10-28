package org.example.app.messaging.content

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Standard base64 (with padding), as Swift's `Data` Codable writes it. */
object Base64Bytes : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}

/**
 * An encrypted attachment on the server. [key] is the per-file AES key, [dg] the SHA-256 of the ciphertext,
 * [sz] and [csz] the plaintext and ciphertext sizes.
 */
@Serializable
class Att(
    val id: String,
    @Serializable(with = Base64Bytes::class) val key: ByteArray,
    @Serializable(with = Base64Bytes::class) val dg: ByteArray,
    val sz: Long,
    val csz: Long,
) {
    override fun equals(other: Any?): Boolean = other is Att && id == other.id && key.contentEquals(other.key) &&
        dg.contentEquals(other.dg) && sz == other.sz && csz == other.csz

    override fun hashCode(): Int = ((id.hashCode() * 31 + key.contentHashCode()) * 31 + dg.contentHashCode()) * 31 + sz.hashCode() + csz.hashCode()
}
