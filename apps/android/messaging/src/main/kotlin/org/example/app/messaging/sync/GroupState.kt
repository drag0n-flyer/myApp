package org.example.app.messaging.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.app.core.groups.SystemLine
import org.example.app.messaging.ChatMapping
import org.example.app.messaging.ports.ChatKind
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.MyState
import org.example.app.messaging.ports.Repository

/**
 * Group rows that change together and by more than one path (a commit that removed this device, the server's member
 * list, leaving): database-only, so the caller runs them in its own transaction.
 */
internal object GroupState {
    /** True for a group chat this account is no longer in, or whose server conversation is the one a late item names. */
    fun isGone(repo: Repository, chatId: String): Boolean = repo.chat(chatId)?.let { it.kind == ChatKind.GROUP && it.myState != MyState.MEMBER } == true

    /** An item for a conversation of a group this account left or was removed from: dropped, never shown, never parked. */
    fun droppedForGone(repo: Repository, conversationId: String): Boolean =
        repo.chats().any { it.kind == ChatKind.GROUP && it.conversationId == conversationId && it.myState != MyState.MEMBER }

    /**
     * This account is no longer in the group: [state] (`removed` or `left`) is set, the old conversation rows go (so a
     * later Welcome for a new MLS group of the same server conversation is not refused as rebound), and a removal gets
     * "You were removed". Does nothing when the chat is not a member any more, so it runs once.
     * The MLS engine has no call to delete a group's state: that stays on the device until the chat is deleted.
     */
    fun markGone(repo: Repository, clock: Clock, chat: ChatRecord, state: String, line: Boolean) {
        val current = repo.chat(chat.id) ?: return
        if (current.kind != ChatKind.GROUP || current.myState != MyState.MEMBER) return
        repo.setMyState(chat.id, state)
        for (c in repo.conversations(chat.id)) {
            repo.deleteConversation(c.serverId)
            ModePin.setPending(repo, c.serverId, false)
            ModePin.clearAnnounce(repo, c.serverId)
            PendingCreate.clear(repo, c.serverId)
            repo.set(Membership.UNREADABLE_PREFIX + c.serverId, ByteArray(0))
        }
        if (line) {
            val now = clock.now()
            // "You were removed", or, when this account left (here or on another of its devices), "You left".
            val body = if (state == MyState.REMOVED) envelope("youWereRemoved", emptyMap()) else envelope("groupLeft", emptyMap())
            insertLine(repo, chat.id, chat.conversationId, "sys-gone-${chat.conversationId}-$now", now, body)
        }
    }

    fun insertLine(repo: Repository, chatId: String, conversationId: String?, id: String, at: Long, envelope: ByteArray) {
        repo.insertMessage(
            MessageRecord(
                id = id, chatId = chatId, conversationId = conversationId, senderAccount = ChatMapping.SYSTEM_SENDER, senderDevice = null,
                sentAt = at, receivedAt = at, envelope = envelope, status = "sent",
            ),
        )
    }

    /** `{"<kind>":{<fields>}}`; a null field is left out. Account IDs are resolved to names when the line is shown. */
    fun envelope(kind: String, fields: Map<String, Any?>): ByteArray {
        val inner = JsonObject(
            fields.mapNotNull { (k, v) ->
                when (v) {
                    null -> null
                    is Boolean -> k to JsonPrimitive(v)
                    else -> k to JsonPrimitive(v.toString())
                }
            }.toMap(),
        )
        return JsonObject(mapOf(kind to inner)).toString().encodeToByteArray()
    }

    /**
     * The chat line for one step of a member-list difference, or null for none. [actor] is the account the server named
     * (a hint, used only for one step); lines about this account's own addition are the Welcome's.
     */
    fun lineFor(line: SystemLine, me: String, actor: String?): ByteArray? {
        fun who(id: String) = if (id == me) null else id
        fun by(a: String?) = mapOf("by" to a?.takeIf { it != me }, "byYou" to (a != null && a == me).takeIf { it })
        return when (line) {
            is SystemLine.Added -> if (line.who == me) null else envelope("groupAdded", by(line.by) + ("who" to line.who))
            is SystemLine.Removed -> envelope("groupRemoved", by(line.by) + ("who" to line.who))
            is SystemLine.Left -> if (line.who == me) null else envelope("groupLeft", mapOf("who" to line.who))
            SystemLine.YouWereRemoved -> envelope("youWereRemoved", emptyMap())
            is SystemLine.MadeAdmin -> envelope("madeAdmin", mapOf("who" to who(line.who)))
            is SystemLine.NoLongerAdmin -> envelope("noLongerAdmin", mapOf("who" to who(line.who)))
        }
    }

    /** A stable ID for a step, so a line is never written twice. */
    fun lineId(conversationId: String, version: Long, index: Int) = "sys-members-$conversationId-$version-$index"
}
