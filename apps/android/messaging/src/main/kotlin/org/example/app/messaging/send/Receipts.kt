package org.example.app.messaging.send

import kotlinx.coroutines.CancellationException
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.ReceiptKind
import org.example.app.messaging.sync.AppliedResult
import org.example.app.messaging.sync.InboxProcessor

/**
 * Delivered and read receipts. A receipt is a control envelope (`k: receipt`) that goes through the outbox like any
 * message. The protocol has no `quiet` flag for receipts, edits or deletes (the owner's decision on one is pending),
 * so none is sent.
 *
 * - Delivered: one receipt per chat per drain, listing the new messages from someone else that the drain stored
 *   (placeholders for undecryptable items are not listed). Sent right away, best effort: a lost one only delays the
 *   tick on the sender's side.
 * - Read: queued behind the chat's other messages ([read]); the caller flushes the outbox.
 *
 * Receiving is `IncomingHandler`'s: statuses only ever move up, so a duplicate or late receipt changes nothing.
 */
class Receipts(
    private val outbox: Outbox,
    /** The Read receipts switch (`SettingsStore` in `:app`), asked at each call. Required: no default. */
    private val readReceiptsEnabled: () -> Boolean,
) {
    /** Registers the delivered receipts on [processor]'s drains. */
    fun attach(processor: InboxProcessor) {
        processor.onBatch = { results -> delivered(results) }
    }

    /** One `delivered` receipt for each chat that got new messages in [results]. */
    suspend fun delivered(results: List<AppliedResult>) {
        val byChat = linkedMapOf<String, MutableList<String>>()
        for (result in results) {
            if (result !is AppliedResult.Stored) continue
            val message = result.message
            if (message.senderAccount == outbox.me.accountId || message.undecryptable) continue
            byChat.getOrPut(message.chatId) { mutableListOf() } += message.id
        }
        for ((chatId, ids) in byChat.toSortedMap()) {
            try {
                outbox.sendControl(chatId) { id, at -> ContentEnvelope.Receipt(id, at, ReceiptKind.Delivered, ids) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    /**
     * Queues a `read` receipt for [ids] in [chatId]. With the switch off nothing is queued, and nothing is sent
     * later for these messages when it goes back on.
     */
    fun read(chatId: String, ids: List<String>) {
        if (ids.isEmpty() || !readReceiptsEnabled()) return
        outbox.enqueueControl(chatId) { id, at -> ContentEnvelope.Receipt(id, at, ReceiptKind.Read, ids) }
    }

    companion object {
        /**
         * The status the UI shows. Incoming read receipts are always stored as `read`; while the switch is off
         * (reciprocal) they are shown as `delivered`, and show as read again when it is turned on.
         */
        fun displayStatus(stored: String, showRead: Boolean): String = if (stored == "read" && !showRead) "delivered" else stored
    }
}
