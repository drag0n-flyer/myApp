package org.example.app.core.chats

import java.time.Instant
import kotlin.math.roundToInt

data class Contact(val id: String, val displayName: String, val username: String)

/** Shown only on the chat info page. */
enum class ChatEncryption(val infoKey: String) {
    EndToEnd("chat_info_encryption_end_to_end"),
    ServerReadable("chat_info_encryption_server_readable"),
}

sealed interface CallSummary {
    data object MissedAudio : CallSummary
    data object MissedVideo : CallSummary
    data class Audio(val duration: Double) : CallSummary
    data class Video(val duration: Double) : CallSummary
}

/** Chat lines for device and safety changes. A null `name` means "You". */
sealed interface SystemEvent {
    data class DeviceAdded(val name: String?) : SystemEvent
    data class DeviceRemoved(val name: String?) : SystemEvent
    data object SafetyNumberChanged : SystemEvent
    data object MessagesPaused : SystemEvent
}

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class Voice(val duration: Double) : MessageContent
    data class Photo(val bytes: Long) : MessageContent
    data class File(val name: String, val bytes: Long) : MessageContent
    data class Call(val summary: CallSummary) : MessageContent
    /** The sender deleted it. */
    data object Deleted : MessageContent
    /** Unknown kind or newer format: "Update [App] to see this message". */
    data object Unsupported : MessageContent
    /** A line in the chat, not a bubble. */
    data class System(val event: SystemEvent) : MessageContent
}

/** Download state of a photo, voice message or file. */
data class MediaState(val attachmentId: String, val phase: Phase) {
    sealed interface Phase {
        data object Remote : Phase
        data class Downloading(val progress: Double) : Phase
        data class Local(val path: String) : Phase
        data object Failed : Phase
    }
}

data class MessageHit(val chatId: String, val messageId: String, val preview: String)

/** 12 groups of 5 digits, shown 3 per row in the Verify screen. */
data class SafetyNumber(val groups: List<String>, val isVerified: Boolean)

enum class DeliveryStatus { Sending, Sent, Delivered, Read }

/** A one-line summary of a message: literal text, or a string resource key with its arguments. */
sealed interface MessagePreview {
    data class Text(val text: String) : MessagePreview
    data class Resource(val key: String, val args: List<String> = emptyList()) : MessagePreview
}

data class Message(
    val id: String,
    val senderId: String,
    val sentAt: Instant,
    val content: MessageContent,
    val status: DeliveryStatus,
    val replyToId: String?,
    val reactions: List<String>,
    val editedAt: Instant? = null,
    val media: MediaState? = null,
    val undecryptable: Boolean = false,
) {
    /** For the chat list. */
    val preview: MessagePreview
        get() = when (val c = content) {
            is MessageContent.Text -> MessagePreview.Text(c.text)
            is MessageContent.Voice -> MessagePreview.Resource("preview_voice", listOf(clock(c.duration)))
            is MessageContent.Photo -> MessagePreview.Resource("preview_photo")
            is MessageContent.File -> MessagePreview.Text(c.name)
            is MessageContent.Call -> when (val s = c.summary) {
                CallSummary.MissedAudio -> MessagePreview.Resource("preview_missed_audio_call")
                CallSummary.MissedVideo -> MessagePreview.Resource("preview_missed_video_call")
                is CallSummary.Audio -> MessagePreview.Resource("preview_audio_call", listOf(minutes(s.duration).toString()))
                is CallSummary.Video -> MessagePreview.Resource("preview_video_call", listOf(minutes(s.duration).toString()))
            }
            MessageContent.Deleted -> MessagePreview.Resource("preview_deleted")
            MessageContent.Unsupported -> MessagePreview.Resource("preview_unsupported")
            is MessageContent.System -> when (val e = c.event) {
                is SystemEvent.DeviceAdded ->
                    if (e.name == null) MessagePreview.Resource("system_device_added_you") else MessagePreview.Resource("system_device_added", listOf(e.name))
                is SystemEvent.DeviceRemoved ->
                    if (e.name == null) MessagePreview.Resource("system_device_removed_you") else MessagePreview.Resource("system_device_removed", listOf(e.name))
                SystemEvent.SafetyNumberChanged -> MessagePreview.Resource("system_safety_number_changed")
                SystemEvent.MessagesPaused -> MessagePreview.Resource("system_messages_paused")
            }
        }

    private companion object {
        fun clock(seconds: Double): String {
            val s = seconds.roundToInt()
            val rest = s % 60
            return "${s / 60}:${if (rest < 10) "0" else ""}$rest"
        }

        fun minutes(seconds: Double): Int = maxOf(1, (seconds / 60).roundToInt())
    }
}

data class Chat(
    val id: String,
    val contactId: String,
    val encryption: ChatEncryption,
    val folderIds: Set<String>,
    val unreadCount: Int,
    val messages: List<Message>,
    val isRequest: Boolean,
    /** The server and the conversation disagree about the chat's mode; sending is paused. */
    val modeMismatch: Boolean = false,
) {
    val lastMessage: Message? get() = messages.lastOrNull()
}
