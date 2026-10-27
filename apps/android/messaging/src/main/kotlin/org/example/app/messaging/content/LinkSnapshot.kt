package org.example.app.messaging.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The device-link snapshot (plan 7), same JSON as the Swift `LinkSnapshot`. [settings] values stay opaque bytes. */
@Serializable
data class LinkSnapshot(
    val v: Int,
    val contacts: List<Contact>,
    val chats: List<Chat>,
    val folders: List<Folder>,
    val blocked: List<String>,
    val settings: Map<String, @Serializable(with = Base64Bytes::class) ByteArray>,
) {
    @Serializable
    data class Contact(
        val accountID: String,
        val username: String,
        val displayName: String,
        val isContact: Boolean,
        /** Milliseconds since 1970. */
        val addedAt: Long,
    )

    @Serializable
    data class Chat(val id: String, val isRequest: Boolean, val muted: Boolean)

    @Serializable
    data class Folder(val id: String, val name: String, val position: Int, val chatIDs: List<String>)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(data: ByteArray): LinkSnapshot = json.decodeFromString(serializer(), data.decodeToString())
    }

    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()
}
