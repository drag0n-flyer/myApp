package org.example.app.core.chats

import kotlinx.coroutines.flow.StateFlow
import org.example.app.core.settings.MediaKind

enum class IncomingCallDecision {
    /** Show the call screen. */
    Ring,
    /** Don't ring; record a missed call in the message request. */
    SilentMissed,
}

/** Chats, contacts and message requests. `SampleChatStore` now; the real MLS-backed store later. */
interface ChatStore {
    val me: Contact
    /** [me] as it changes: the person's own name shows from here, so a new name appears without a restart. */
    val meState: StateFlow<Contact>
    /** Puts the person's own display name in the store (and on disk); the server call is the caller's. */
    fun setMyDisplayName(name: String)
    /** The name stored for the person's own account, or "" when none is known (then [me] shows the fallback "You"). */
    val myStoredName: String
    val contacts: StateFlow<List<Contact>>
    val folders: StateFlow<List<ChatFolder>>
    /** Chats that are not message requests, pinned first, then newest first. Archived chats are included: see [ChatListOrder.visible]. */
    val chats: StateFlow<List<Chat>>
    val requests: StateFlow<List<Chat>>
    /** Account IDs this device has blocked. */
    val blockedIds: StateFlow<Set<String>>
    val reportedChatIds: StateFlow<Set<String>>
    /** Chats muted on this device (requests included): notifications stay quiet and the launcher badge skips them. */
    val mutedChatIds: StateFlow<Set<String>>

    /** The chats a folder shows: archived chats are in the archive, not in any folder. */
    fun chats(folder: ChatFolder): List<Chat> = ChatListOrder.visible(chats.value, archive = false).filter { chat ->
        when (folder.kind) {
            ChatFolder.Kind.All -> true
            ChatFolder.Kind.Unread -> chat.unreadCount > 0
            ChatFolder.Kind.Custom -> folder.id in chat.folderIds
        }
    }

    /** Number shown on the Unread chip. */
    fun unreadChatCount(): Int = ChatListOrder.visible(chats.value, archive = false).count { it.unreadCount > 0 }

    /** Pins or unpins. Returns false, changing nothing, when pinning would make more than [ChatListOrder.MAX_PINS]. */
    fun setPinned(chatId: String, pinned: Boolean): Boolean
    fun setMuted(chatId: String, muted: Boolean)
    /** An archived chat still receives, stores and counts messages; it just leaves the main list. */
    fun setArchived(chatId: String, archived: Boolean)
    /** Saves the unsent composer text for the chat; null or blank removes it. */
    fun setDraft(chatId: String, text: String?)

    fun chat(id: String): Chat?
    fun contact(id: String): Contact?

    /** Up to `limit` messages before `messageId` (the newest if null), oldest first. */
    fun messages(chatId: String, before: String?, limit: Int): List<Message>
    fun searchMessages(query: String, limit: Int): List<MessageHit>
    fun send(content: MessageContent, chatId: String, replyTo: String? = null)
    fun sendAttachment(path: String, kind: MediaKind, chatId: String)
    /** The largest attachment the server takes, in bytes: a video above it is refused before anything is staged. */
    val attachmentLimitBytes: Long get() = 104_857_600L
    /**
     * Sends a preview's plan ([SendPlan.plan]) in order: each attachment, and the caption as a text message right after
     * the first. The default does them one after the other; a store that creates attachments asynchronously keeps the order.
     */
    fun sendBatch(sends: List<SendPlan.Send>, chatId: String) {
        for (item in sends) when (item) {
            is SendPlan.Send.Attachment -> sendAttachment(item.picked.path, item.picked.kind, chatId)
            is SendPlan.Send.Text -> send(MessageContent.Text(item.text), chatId)
        }
    }
    /**
     * The fraction (0.0 to 1.0) uploaded of each outgoing attachment that is still uploading, by message id. A message
     * is in the map from the moment it shows as sending until it is queued, failed or cancelled.
     */
    val uploadProgress: StateFlow<Map<String, Double>> get() = NO_UPLOADS
    /**
     * Cancel on an outgoing attachment: while it is queued or uploading it stops, and the message, its staged files and
     * any encrypted copy are removed (the other side never sees it). Once it is sent this does nothing.
     */
    fun cancelUpload(messageId: String) {}
    /**
     * The last attachment in [chatId] that was dropped (too big, unreadable) and not yet shown, or null. It is kept until
     * the chat screen has shown it and calls [takeSendProblem], so leaving the chat early loses nothing. A message that
     * failed but is kept shows as [DeliveryStatus.Failed] instead.
     */
    fun sendProblem(chatId: String): kotlinx.coroutines.flow.Flow<SendProblem.Reason?> = kotlinx.coroutines.flow.flowOf(null)
    /** Forgets the problem of [chatId] once it has been shown. */
    fun takeSendProblem(chatId: String) {}
    fun react(emoji: String, messageId: String, chatId: String)
    fun edit(messageId: String, chatId: String, text: String)
    fun delete(messageId: String, chatId: String)
    fun download(messageId: String, chatId: String)
    /** Puts a message that failed to send back in the queue (`DeliveryStatus.Failed`). Same message, same wire format. */
    fun retrySend(messageId: String) {}
    /** Suspends until every write (react, edit, delete, retry) started so far has landed in the database. Default: nothing to wait for. */
    suspend fun awaitWrites() {}
    fun recordCall(summary: CallSummary, contactId: String, outgoing: Boolean, at: java.time.Instant)

    /** False while the engine cannot give real member keys: the Verify screen and the Verified seal stay hidden. */
    val supportsSafetyNumbers: Boolean
    /** Null until the chat exists. */
    fun safetyNumber(chatId: String): SafetyNumber?
    /** True when one of the chat's groups could not be read; chat info then says so and hides Verify. */
    fun membersUnreadable(chatId: String): Boolean = false
    /** Local only. */
    fun setVerified(verified: Boolean, chatId: String)

    fun receive(content: MessageContent, from: Contact)
    fun accept(requestId: String)
    fun block(contactId: String)
    /** Lifts a block. The contact does not come back by itself; the chat starts again when either side writes. */
    fun unblock(contactId: String)
    fun report(chatId: String)
    fun markRead(chatId: String)
    fun incomingCall(fromAccountId: String, video: Boolean = false): IncomingCallDecision
    suspend fun addContact(username: String): Contact?
    /** Adds someone the server already named (a redeemed one-time link), without a username lookup. The default looks them up. */
    suspend fun addKnownContact(accountId: String, username: String, displayName: String): Contact? = addContact(username)

    /** The chat with this contact, created empty if there is none yet. */
    fun ensureChat(contactId: String): String

    // People base (disappearing messages and profiles). Defaults suit stores that keep none of it.

    /** The chat's disappearing-message timer in seconds; 0 is off. */
    fun timerSeconds(chatId: String): Int = 0
    /** Sets the chat's timer on this device. Later messages sent here carry it as `ttl`. Sending the `timer` message is the caller's. */
    fun setTimer(chatId: String, seconds: Int) {}
    /** Removes every message that expired by [now] (rows, files, search entries) and returns their IDs, to cancel their notifications. */
    fun sweepExpired(now: Long): List<String> = emptyList()
    /** The newest profile a contact sent, or null. */
    fun profile(accountId: String): org.example.app.core.people.Profile? = null
    /** Keeps the profile only when [ver] is higher than the stored one; returns whether it was kept. */
    fun saveProfile(accountId: String, name: String, photo: ByteArray?, ver: Int): Boolean = false

    /**
     * Every profile this device shows, by account ID: each contact's newest, and the person's own photo (name as stored).
     * One helper reads it for tiles; a screen never reads the profile table itself.
     */
    val profiles: StateFlow<Map<String, org.example.app.core.people.Profile>> get() = NO_PROFILES
    /** Sets or removes (null) the person's own photo, a JPEG that passes `ProfilePhotoRule`, and tells the contacts. */
    fun setMyPhoto(photo: ByteArray?) {}
    /** Tells the contacts the person's name and photo when they differ from what was last announced. */
    fun announceProfile() {}

    /** Custom folders only; All and Unread are fixed. */
    fun addFolder(name: String, chatIds: Set<String>): ChatFolder
    fun renameFolder(id: String, name: String)
    fun deleteFolder(id: String)
    fun moveFolder(id: String, toCustomIndex: Int)
}

private val NO_UPLOADS: StateFlow<Map<String, Double>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
private val NO_PROFILES: StateFlow<Map<String, org.example.app.core.people.Profile>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

/** An attachment in [chatId] that was dropped, and why. */
data class SendProblem(val chatId: String, val reason: Reason) {
    enum class Reason { TooBig, Unreadable }
}
