package org.example.app.messaging.sync

import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.GroupId
import org.example.app.messaging.ports.MessageRecord

/** This device. Its MLS identity is `"<accountId>/<deviceId>"`. */
data class LocalDevice(val accountId: String, val deviceId: String) {
    val identity: String get() = "$accountId/$deviceId"
}

sealed class SyncException(message: String) : Exception(message) {
    /** The commit was rejected with `stale_epoch` on every attempt. */
    object EpochConflict : SyncException("epochConflict")
    /** `announceMode` on a conversation whose `innerMode` is still null. */
    object ModeUnknown : SyncException("modeUnknown")
    /** Neither the peer nor my other devices had a KeyPackage to claim. */
    object NoKeyPackages : SyncException("noKeyPackages")
    /** A Welcome for a group that has no member of another account. */
    object NoPeer : SyncException("noPeer")
    /** No escrow sealer, so a server-readable conversation's escrow copy cannot be made. */
    object EscrowKeyUnavailable : SyncException("escrowKeyUnavailable")
    /** My earlier commit is still pending and the server has applied it; its echo has not been processed yet. */
    object CommitInFlight : SyncException("commitInFlight")
}

/** What `IncomingHandler.apply` did with one processed item. */
sealed interface AppliedResult {
    /** A new message row (also one shown as "Update [App] to see this message"). */
    data class Stored(val message: MessageRecord) : AppliedResult
    /** A call signal. Never stored; [sender] is the MLS sender `"<accountId>/<deviceId>"`. */
    data class CallSignal(val payload: CallSignalPayload, val sender: String) : AppliedResult
    /** A Welcome; the processor stored its conversation in the same transaction. */
    data class Joined(val group: GroupId, val epoch: Long) : AppliedResult
    /** A commit by another device was applied. */
    data class Commit(val conversationId: String, val newEpoch: Long) : AppliedResult
    /** A commit removed this device; the conversation is now inactive. */
    data class RemovedMe(val conversationId: String) : AppliedResult
    /** Applied without a new message (edit, delete, reaction, receipt, mode) or dropped. */
    data object Ignored : AppliedResult
}

/**
 * Seals the escrow copy of an envelope for a server-readable conversation (Design 18). D5's `Escrow` provides it;
 * until then [ConversationManager] refuses to post into a conversation that needs one.
 */
fun interface EscrowSealer {
    suspend fun seal(plaintext: ByteArray, at: Long, conversationId: String): ByteArray
}
