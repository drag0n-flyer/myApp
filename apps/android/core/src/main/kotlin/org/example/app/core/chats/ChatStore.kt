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
    val contacts: StateFlow<List<Contact>>
    val folders: StateFlow<List<ChatFolder>>
    /** Chats that are not message requests, newest first. */
    val chats: StateFlow<List<Chat>>
    val requests: StateFlow<List<Chat>>
    /** Account IDs this device has blocked. */
    val blockedIds: StateFlow<Set<String>>
    val reportedChatIds: StateFlow<Set<String>>

    fun chats(folder: ChatFolder): List<Chat> = chats.value.filter { chat ->
        when (folder.kind) {
            ChatFolder.Kind.All -> true
            ChatFolder.Kind.Unread -> chat.unreadCount > 0
            ChatFolder.Kind.Custom -> folder.id in chat.folderIds
        }
    }

    /** Number shown on the Unread chip. */
    fun unreadChatCount(): Int = chats.value.count { it.unreadCount > 0 }

    fun chat(id: String): Chat?
    fun contact(id: String): Contact?

    /** Up to `limit` messages before `messageId` (the newest if null), oldest first. */
    fun messages(chatId: String, before: String?, limit: Int): List<Message>
    fun searchMessages(query: String, limit: Int): List<MessageHit>
    fun send(content: MessageContent, chatId: String, replyTo: String? = null)
    fun sendAttachment(path: String, kind: MediaKind, chatId: String)
    fun react(emoji: String, messageId: String, chatId: String)
    fun edit(messageId: String, chatId: String, text: String)
    fun delete(messageId: String, chatId: String)
    fun download(messageId: String, chatId: String)
    fun recordCall(summary: CallSummary, contactId: String, outgoing: Boolean, at: java.time.Instant)

    /** False while the engine cannot give real member keys: the Verify screen and the Verified seal stay hidden. */
    val supportsSafetyNumbers: Boolean
    /** Null until the chat exists. */
    fun safetyNumber(chatId: String): SafetyNumber?
    /** Local only. */
    fun setVerified(verified: Boolean, chatId: String)

    fun receive(content: MessageContent, from: Contact)
    fun accept(requestId: String)
    fun block(contactId: String)
    fun report(chatId: String)
    fun markRead(chatId: String)
    fun incomingCall(fromAccountId: String, video: Boolean = false): IncomingCallDecision
    suspend fun addContact(username: String): Contact?

    /** The chat with this contact, created empty if there is none yet. */
    fun ensureChat(contactId: String): String

    /** Custom folders only; All and Unread are fixed. */
    fun addFolder(name: String, chatIds: Set<String>): ChatFolder
    fun renameFolder(id: String, name: String)
    fun deleteFolder(id: String)
    fun moveFolder(id: String, toCustomIndex: Int)
}
