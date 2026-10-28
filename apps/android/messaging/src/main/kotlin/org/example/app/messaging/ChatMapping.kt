package org.example.app.messaging

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MediaState
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SystemEvent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.AttachmentRecord
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ConversationMode
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.ReactionRecord
import org.example.app.messaging.send.Receipts

/** Database records to the `:core` types the UI shows. Pure: it reads nothing but its arguments. */
internal object ChatMapping {
    /** Rows written by `recordCall` use this ID prefix; their envelope is the JSON of the call summary. */
    const val CALL_PREFIX = "call-"
    const val SYSTEM_SENDER = "system"
    const val JOIN_FAILED_PREFIX = "join-failed-"

    /** A message ID that only this device may create. */
    fun isReservedId(id: String): Boolean = id.startsWith(CALL_PREFIX) || id.startsWith(JOIN_FAILED_PREFIX)

    fun status(stored: String, showRead: Boolean): DeliveryStatus = when (Receipts.displayStatus(stored, showRead)) {
        "delivered" -> DeliveryStatus.Delivered
        "read" -> DeliveryStatus.Read
        "sent" -> DeliveryStatus.Sent
        // `failed` has no `:core` case: it shows as not yet sent. `LiveChatStore.failedMessageIds` offers the retry.
        else -> DeliveryStatus.Sending
    }

    fun mediaState(r: AttachmentRecord): MediaState {
        val phase = when (r.state) {
            "local" -> r.localPath?.let { MediaState.Phase.Local(it) } ?: MediaState.Phase.Failed
            "downloading" -> MediaState.Phase.Downloading(if (r.cipherSize > 0) minOf(r.downloaded.toDouble() / r.cipherSize, 1.0) else 0.0)
            "failed" -> MediaState.Phase.Failed
            else -> MediaState.Phase.Remote
        }
        return MediaState(r.id, phase)
    }

    fun message(
        record: MessageRecord, attachment: AttachmentRecord?, reactions: List<ReactionRecord>, showRead: Boolean,
        displayName: (String) -> String?,
    ): Message {
        var message = Message(
            id = record.id, senderId = record.senderAccount, sentAt = Instant.ofEpochMilli(record.sentAt),
            content = MessageContent.Unsupported, status = status(record.status, showRead), replyToId = record.replyTo,
            reactions = reactions.map { it.emoji }, editedAt = record.editedAt?.let(Instant::ofEpochMilli),
            media = null, undecryptable = record.undecryptable,
        )
        if (record.deleted) return message.copy(content = MessageContent.Deleted, reactions = emptyList())
        if (record.undecryptable) return message
        if (record.senderAccount == SYSTEM_SENDER) {
            systemEvent(record.envelope, displayName)?.let { message = message.copy(content = MessageContent.System(it)) }
            return message
        }
        if (record.id.startsWith(CALL_PREFIX)) {
            callSummary(record.envelope)?.let { message = message.copy(content = MessageContent.Call(it)) }
            return message
        }
        val content = when (val e = ContentCodec.decode(record.envelope)) {
            is ContentEnvelope.Text -> MessageContent.Text(e.t)
            is ContentEnvelope.Photo -> MessageContent.Photo(e.a.sz)
            is ContentEnvelope.Voice -> MessageContent.Voice(e.dur)
            is ContentEnvelope.File -> MessageContent.File(e.name, e.a.sz)
            else -> MessageContent.Unsupported // control kinds are never stored as messages
        }
        message = message.copy(content = content)
        if (content is MessageContent.Photo || content is MessageContent.Voice || content is MessageContent.File) {
            message = message.copy(media = attachment?.let(::mediaState))
        }
        return message
    }

    /** `{"deviceAdded":{"name":"<account>"}}` (no name = "You"); the account becomes a display name here. */
    private fun systemEvent(envelope: ByteArray, displayName: (String) -> String?): SystemEvent? {
        val obj = runCatching { Json.parseToJsonElement(envelope.decodeToString()).jsonObject }.getOrNull() ?: return null
        fun name(o: JsonObject): String? = o["name"]?.jsonPrimitive?.contentOrNull?.let { displayName(it) ?: "Someone" }
        obj["deviceAdded"]?.jsonObject?.let { return SystemEvent.DeviceAdded(name(it)) }
        obj["deviceRemoved"]?.jsonObject?.let { return SystemEvent.DeviceRemoved(name(it)) }
        if ("safetyNumberChanged" in obj) return SystemEvent.SafetyNumberChanged
        if ("messagesPaused" in obj) return SystemEvent.MessagesPaused
        return null
    }

    fun callEnvelope(summary: CallSummary): ByteArray = when (summary) {
        CallSummary.MissedAudio -> """{"kind":"missedAudio"}"""
        CallSummary.MissedVideo -> """{"kind":"missedVideo"}"""
        is CallSummary.Audio -> """{"kind":"audio","duration":${summary.duration}}"""
        is CallSummary.Video -> """{"kind":"video","duration":${summary.duration}}"""
    }.encodeToByteArray()

    private fun callSummary(envelope: ByteArray): CallSummary? {
        val obj = runCatching { Json.parseToJsonElement(envelope.decodeToString()).jsonObject }.getOrNull() ?: return null
        val duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        return when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "missedAudio" -> CallSummary.MissedAudio
            "missedVideo" -> CallSummary.MissedVideo
            "audio" -> CallSummary.Audio(duration)
            "video" -> CallSummary.Video(duration)
            else -> null
        }
    }

    /** `Chat.encryption` follows the pinned `innerMode` of the preferred conversation only; the server's `mode` is never trusted. */
    fun encryption(preferred: ConversationRecord?): ChatEncryption =
        if (preferred?.innerMode == ConversationMode.SERVER_READABLE) ChatEncryption.ServerReadable else ChatEncryption.EndToEnd

    fun chat(
        record: ChatRecord, conversations: List<ConversationRecord>, preferred: ConversationRecord?, folderIds: Set<String>,
        messages: List<Message>,
    ) = Chat(
        id = record.id, contactId = record.id, encryption = encryption(preferred), folderIds = folderIds, unreadCount = record.unread,
        messages = messages, isRequest = record.isRequest, modeMismatch = conversations.any { it.modeMismatch },
    )

    /** A stored number (12 groups of 5 digits, joined) split into its groups. */
    fun groups(number: String): List<String> = number.chunked(5)
}
