package org.example.app.core.chats

import java.time.YearMonth
import java.time.ZoneId
import org.example.app.core.settings.MediaKind

/** One photo or video chosen in the picker: [path] is a file or `content:` URI the store can stage, [bytes] its size (0 when unknown). */
data class Picked(val path: String, val kind: MediaKind, val bytes: Long)

/** What a send from the preview screen does, in order, and when it is refused (before anything is staged or uploaded). */
object SendPlan {
    const val MAX_ITEMS = 10

    /** One attachment to send; the [caption] (null for none) travels in the same message. */
    sealed interface Send {
        val picked: Picked
        val caption: String?
        data class Attachment(override val picked: Picked, override val caption: String? = null) : Send
    }

    enum class Refusal { TooMany, VideoTooBig }

    sealed interface Result {
        data class Ok(val sends: List<Send>) : Result
        data class Refused(val reason: Refusal) : Result
    }

    /** The items in order; the trimmed [caption] rides on the first one (its `c` field), and is no caption when blank. */
    fun plan(items: List<Picked>, caption: String, limitBytes: Long): Result {
        if (items.size > MAX_ITEMS) return Result.Refused(Refusal.TooMany)
        if (items.any { it.kind == MediaKind.Video && it.bytes > limitBytes }) return Result.Refused(Refusal.VideoTooBig)
        val text = caption.trim()
        val sends = buildList<Send> {
            items.forEachIndexed { index, picked ->
                add(Send.Attachment(picked, if (index == 0 && text.isNotEmpty()) text else null))
            }
        }
        return Result.Ok(sends)
    }
}

/** The gallery's month sections. */
object GalleryGroups {
    data class Group(val month: YearMonth, val items: List<Message>)

    /** Newest month first, newest item first inside a month; the month is the one the item falls in for [zone]. */
    fun byMonth(items: List<Message>, zone: ZoneId): List<Group> =
        items.sortedByDescending { it.sentAt }
            .groupBy { YearMonth.from(it.sentAt.atZone(zone)) }
            .map { (month, list) -> Group(month, list) }
            .sortedByDescending { it.month }
}

/** What Cancel does to an outgoing attachment. */
object UploadCancel {
    enum class Phase { Queued, Uploading, Sent }
    enum class Action { RemoveUnsent, Keep }

    fun onCancel(phase: Phase): Action = when (phase) {
        Phase.Queued, Phase.Uploading -> Action.RemoveUnsent
        Phase.Sent -> Action.Keep
    }
}
