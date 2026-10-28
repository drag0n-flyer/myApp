package org.example.app.live

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.app.core.chats.CallSummary
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatFolder
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.Contact
import org.example.app.core.chats.IncomingCallDecision
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessageHit
import org.example.app.core.chats.SafetyNumber
import org.example.app.core.settings.MediaKind

/**
 * Stands in while no account is open in the live build (between a session's end and onboarding, or while the session
 * opens): no data, and every change does nothing. Never sample data.
 */
object EmptyChatStore : ChatStore {
    override val me = Contact("", "", "")
    override val contacts: StateFlow<List<Contact>> = MutableStateFlow(emptyList())
    override val folders: StateFlow<List<ChatFolder>> = MutableStateFlow(listOf(ChatFolder.All, ChatFolder.Unread))
    override val chats: StateFlow<List<Chat>> = MutableStateFlow(emptyList())
    override val requests: StateFlow<List<Chat>> = MutableStateFlow(emptyList())
    override val blockedIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val reportedChatIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val supportsSafetyNumbers = false

    override fun chat(id: String): Chat? = null
    override fun contact(id: String): Contact? = null
    override fun messages(chatId: String, before: String?, limit: Int): List<Message> = emptyList()
    override fun searchMessages(query: String, limit: Int): List<MessageHit> = emptyList()
    override fun send(content: MessageContent, chatId: String, replyTo: String?) = Unit
    override fun sendAttachment(path: String, kind: MediaKind, chatId: String) = Unit
    override fun react(emoji: String, messageId: String, chatId: String) = Unit
    override fun edit(messageId: String, chatId: String, text: String) = Unit
    override fun delete(messageId: String, chatId: String) = Unit
    override fun download(messageId: String, chatId: String) = Unit
    override fun recordCall(summary: CallSummary, contactId: String, outgoing: Boolean, at: Instant) = Unit
    override fun safetyNumber(chatId: String): SafetyNumber? = null
    override fun setVerified(verified: Boolean, chatId: String) = Unit
    override fun receive(content: MessageContent, from: Contact) = Unit
    override fun accept(requestId: String) = Unit
    override fun block(contactId: String) = Unit
    override fun report(chatId: String) = Unit
    override fun markRead(chatId: String) = Unit
    override fun incomingCall(fromAccountId: String, video: Boolean) = IncomingCallDecision.SilentMissed
    override suspend fun addContact(username: String): Contact? = null
    override fun ensureChat(contactId: String): String = contactId
    override fun addFolder(name: String, chatIds: Set<String>): ChatFolder = ChatFolder("", name, ChatFolder.Kind.Custom)
    override fun renameFolder(id: String, name: String) = Unit
    override fun deleteFolder(id: String) = Unit
    override fun moveFolder(id: String, toCustomIndex: Int) = Unit
}
