package org.example.app.messaging.sync

import java.util.Locale
import org.example.app.contracts.Incoming
import org.example.app.contracts.MemberKey
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.ReceiptKind
import org.example.app.messaging.ports.AttachmentRecord
import org.example.app.messaging.ports.AttachmentState
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.RawInboxRecord
import org.example.app.messaging.ports.Repository

/**
 * Applies one processed MLS result to the database. Shared by the inbox processor and (D7) the notification
 * processor. Synchronous and database-only, so the caller runs it in the transaction that also marks the item
 * processed and deletes the raw row. Never logs message content.
 *
 * Trust: the sender is the MLS credential (`Incoming.Application.sender`), never the server's
 * `senderAccountId`; the conversation is the one stored for the MLS group, never the server's `conversationId`.
 *
 * @param fold search folding for the `folded` column. The app passes `:store`'s `TextFold::fold`.
 */
class IncomingHandler(
    val me: LocalDevice,
    private val clock: Clock,
    private val fold: (String) -> String = { it.lowercase(Locale.ROOT) },
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    /**
     * Design 17: my own accepted commit comes back as an inbox item. When the stored epoch is already past it there
     * is nothing to merge and the item is skipped; otherwise (a crash between the server's 200 and `commitAccepted`)
     * it goes to the engine, which merges the pending commit.
     */
    fun isEchoedOwnCommit(raw: RawInboxRecord, repo: Repository): Boolean {
        if (raw.kind != "commit" || raw.senderAccount != me.accountId || raw.senderDevice != me.deviceId) return false
        val conversation = repo.conversation(raw.conversationId) ?: return false
        return conversation.epoch >= raw.epoch + 1
    }

    /**
     * @param memberKeys the group's members after a commit or Welcome (`Membership.keys`, read under the MLS lock
     *   before the transaction). Null skips the members and the safety number; the next update catches up.
     */
    fun apply(incoming: Incoming, raw: RawInboxRecord, repo: Repository, memberKeys: List<MemberKey>? = null): AppliedResult =
        when (incoming) {
            is Incoming.Joined -> {
                repo.conversationByGroup(incoming.group.raw)?.let { c -> memberKeys?.let { Membership.write(me, it, c, repo) } }
                AppliedResult.Joined(incoming.group, incoming.epoch)
            }
            is Incoming.Commit -> {
                val conversation = repo.conversationByGroup(incoming.group.raw)
                when {
                    conversation == null -> AppliedResult.Ignored
                    incoming.removedMe -> {
                        repo.upsertConversation(conversation.copy(active = false))
                        AppliedResult.RemovedMe(conversation.serverId)
                    }
                    else -> {
                        repo.setEpoch(conversation.serverId, incoming.newEpoch)
                        memberKeys?.let { Membership.write(me, it, conversation, repo) }
                        AppliedResult.Commit(conversation.serverId, incoming.newEpoch)
                    }
                }
            }
            is Incoming.Application -> {
                val conversation = repo.conversationByGroup(incoming.group.raw)
                if (conversation == null) AppliedResult.Ignored
                else applyApplication(incoming.sender, incoming.plaintext, raw, conversation, repo)
            }
        }

    /**
     * An MLS failure: one placeholder row (`undecryptable`, ID `undecryptable-<cursor>`) in the chat the server named,
     * or nothing when that conversation is not known (a failed Welcome).
     */
    fun storePlaceholder(raw: RawInboxRecord, repo: Repository) {
        val conversation = repo.conversation(raw.conversationId) ?: return
        val id = "undecryptable-${raw.cursor}"
        val envelope = """{"at":${raw.sentAt},"id":"$id","k":"undecryptable","v":1}""".encodeToByteArray()
        val record = MessageRecord(
            id = id, chatId = conversation.chatId, conversationId = conversation.serverId, senderAccount = raw.senderAccount,
            senderDevice = raw.senderDevice, sentAt = raw.sentAt, receivedAt = clock.now(), envelope = envelope,
            status = "delivered", undecryptable = true,
        )
        if (repo.insertMessage(record)) bumpChat(record, repo)
    }

    private fun applyApplication(
        sender: String, plaintext: ByteArray, raw: RawInboxRecord, conversation: ConversationRecord, repo: Repository,
    ): AppliedResult {
        val account = sender.substringBefore('/')
        val device = sender.substringAfter('/', "").ifEmpty { null }
        val envelope = ContentCodec.decode(plaintext)
        val chatId = conversation.chatId

        fun store(replyTo: String? = null, folded: String = ""): AppliedResult {
            val record = MessageRecord(
                id = envelope.id.ifEmpty { "unsupported-${raw.cursor}" }, chatId = chatId, conversationId = conversation.serverId,
                senderAccount = account, senderDevice = device, sentAt = envelope.at, receivedAt = clock.now(), envelope = plaintext,
                status = if (account == me.accountId) "sent" else "delivered", replyTo = replyTo, folded = folded,
            )
            if (!repo.insertMessage(record)) return AppliedResult.Ignored
            bumpChat(record, repo)
            return AppliedResult.Stored(record)
        }

        fun media(att: Att, kind: String, reply: String?, folded: String = ""): AppliedResult {
            val result = store(reply, folded)
            if (result is AppliedResult.Stored) {
                repo.upsertAttachment(
                    AttachmentRecord(att.id, result.message.id, kind, att.key, att.dg, att.sz, att.csz, AttachmentState.REMOTE, 0, null),
                )
            }
            return result
        }

        return when (envelope) {
            is ContentEnvelope.Text -> store(envelope.r, fold(envelope.t))
            is ContentEnvelope.Photo -> media(envelope.a, "photo", envelope.r)
            is ContentEnvelope.Voice -> media(envelope.a, "voice", envelope.r)
            is ContentEnvelope.File -> media(envelope.a, "file", envelope.r, fold(envelope.name))
            is ContentEnvelope.Unknown -> store()
            is ContentEnvelope.Edit -> {
                val message = repo.message(envelope.target)
                val old = message?.let { ContentCodec.decode(it.envelope) }
                if (message != null && message.chatId == chatId && !message.deleted && old is ContentEnvelope.Text) {
                    val edited = ContentCodec.encode(ContentEnvelope.Text(old.id, old.at, envelope.t, old.r))
                    // The sender check is in the query: an edit by anyone else changes nothing.
                    repo.applyEdit(envelope.target, account, edited, fold(envelope.t), envelope.at)
                }
                AppliedResult.Ignored
            }
            is ContentEnvelope.Delete -> {
                val message = repo.message(envelope.target)
                if (message != null && message.chatId == chatId) repo.markDeleted(envelope.target, account)
                AppliedResult.Ignored
            }
            is ContentEnvelope.React -> {
                val message = repo.message(envelope.target)
                if (message != null && message.chatId == chatId && !message.deleted) repo.setReaction(message.id, account, envelope.e)
                AppliedResult.Ignored
            }
            is ContentEnvelope.Receipt -> {
                applyReceipt(envelope.s, envelope.ids, account, chatId, repo)
                AppliedResult.Ignored
            }
            is ContentEnvelope.CallSig -> AppliedResult.CallSignal(envelope.p, sender)
            is ContentEnvelope.Mode -> {
                ModePin.pin(repo, conversation, envelope.m, clock.now(), log)
                AppliedResult.Ignored
            }
        }
    }

    /** A receipt counts when it is about my own messages (from the peer), or comes from one of my own devices. */
    private fun applyReceipt(kind: ReceiptKind, ids: List<String>, from: String, chatId: String, repo: Repository) {
        val fromMe = from == me.accountId
        val valid = mutableListOf<String>()
        var unreadCleared = 0
        for (id in ids) {
            val message = repo.message(id) ?: continue
            if (message.chatId != chatId) continue
            if (message.senderAccount != me.accountId && !fromMe) continue
            valid += id
            if (fromMe && kind == ReceiptKind.Read && message.senderAccount != me.accountId && message.status != "read") unreadCleared += 1
        }
        repo.updateStatus(valid, kind.wire)
        if (unreadCleared > 0) repo.chat(chatId)?.let { repo.setUnread(chatId, maxOf(0, it.unread - unreadCleared)) }
    }

    /** An incoming message from someone else counts as unread and moves the chat up. */
    private fun bumpChat(message: MessageRecord, repo: Repository) {
        val chat = repo.chat(message.chatId) ?: return
        repo.upsertChat(
            chat.copy(
                unread = chat.unread + if (message.senderAccount != me.accountId) 1 else 0,
                lastActivity = maxOf(chat.lastActivity, minOf(message.sentAt, clock.now())),
            ),
        )
    }
}

