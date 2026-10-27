package org.example.app.messaging.sync

import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.Repository

/**
 * The conversation's mode is bound inside the conversation (Design 18).
 *
 * Sender side: the creator stores its conversation with `innerMode` already set, so "the `mode` envelope has not
 * been sent yet" is a `kv` marker, `mode-pending:<serverId>` (non-empty = pending). Every send path goes through
 * `ConversationManager.preferredConversation`, which sends a pending envelope before anything else.
 *
 * Receiver side: the first `mode` envelope pins `innerMode`; a later different one is ignored, sets
 * `modeMismatch` and adds one "Messages in this chat are paused" line.
 */
object ModePin {
    fun pendingKey(serverId: String) = "mode-pending:$serverId"

    fun isPending(repo: Repository, serverId: String): Boolean = repo.get(pendingKey(serverId))?.isNotEmpty() == true

    fun setPending(repo: Repository, serverId: String, pending: Boolean) =
        repo.set(pendingKey(serverId), if (pending) byteArrayOf(1) else ByteArray(0))

    /** Pins [mode] on [conversation]. Returns false on a mismatch (logged with the ID only, line added once). */
    fun pin(repo: Repository, conversation: ConversationRecord, mode: String, now: Long, log: (String) -> Unit): Boolean {
        if (repo.pinInnerMode(conversation.serverId, mode)) return true
        log("mode_mismatch conversation=${conversation.serverId}")
        addPausedLine(repo, conversation, now)
        return false
    }

    /** The `SystemEvent.messagesPaused` line as the iPhone encodes it. The ID has no epoch, so it is stored once. */
    const val PAUSED_ENVELOPE = """{"messagesPaused":{}}"""

    fun addPausedLine(repo: Repository, conversation: ConversationRecord, now: Long) {
        repo.insertMessage(
            MessageRecord(
                id = "sys-${conversation.serverId}-messagesPaused", chatId = conversation.chatId, conversationId = conversation.serverId,
                senderAccount = "system", senderDevice = null, sentAt = now, receivedAt = now,
                envelope = PAUSED_ENVELOPE.encodeToByteArray(), status = "sent",
            ),
        )
    }
}
