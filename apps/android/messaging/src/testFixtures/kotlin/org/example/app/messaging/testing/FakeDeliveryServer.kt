package org.example.app.messaging.testing

import java.io.InputStream
import java.time.Instant
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.DeviceRecord
import org.example.app.contracts.InboxItem
import org.example.app.contracts.InboxPage
import org.example.app.contracts.ServerException

/**
 * The delivery part of the Go server in memory, shared by several devices. [api] gives one device's view.
 *
 * Fan-out follows the server design: a commit goes to every member device before the change, the sender
 * included (the echo, Design 17), and to removed devices; its Welcome goes to the added devices; a message goes
 * to every member device but the sender. Conversation IDs are `conv-0001`, `conv-0002`, ... so string order is
 * creation order.
 */
class FakeDeliveryServer(var defaultMode: String = "e2ee") {
    class Conversation(val id: String, var mode: String, var epoch: Long, val members: java.util.TreeSet<String>)

    class Posted(val conversationId: String, val epoch: Long, val payload: ByteArray, val escrow: ByteArray?, val clientMsgId: String, val sender: String)

    private val lock = Any()
    private val keyPackages = HashMap<String, LinkedHashMap<String, ArrayDeque<ByteArray>>>() // account -> device -> packages
    private val conversations = LinkedHashMap<String, Conversation>()
    private val inboxes = HashMap<String, MutableList<InboxItem>>() // identity -> items
    private var cursor = 0L
    private var conversationCounter = 0

    /** Every application message the server accepted, in order. */
    val messages = mutableListOf<Posted>()

    /** Every commit post that reached the server (accepted or not), as `(identity, conversationId, epoch)`. */
    val commitPosts = mutableListOf<Triple<String, String, Long>>()

    /** Calls in order, as `"<identity> <call>"`. */
    val calls = mutableListOf<String>()

    var rejectNextCommits = 0
    var loseNextCommitResponses = 0

    /**
     * The next commit requests time out on the client before they reach the server: the client gets a network
     * error and nothing is applied. They stay in flight until [releaseHeldCommits] lands them (a request that
     * arrives after the client gave up), or for ever.
     */
    var holdNextCommitRequests = 0

    private class HeldCommit(val identity: String, val id: String, val epoch: Long, val commit: ByteArray, val welcome: ByteArray?, val add: List<String>, val remove: List<String>)
    private val held = mutableListOf<HeldCommit>()

    /** Runs right after a commit is applied, before the reply leaves (a test throws here to model a kill). */
    var afterCommitApplied: (() -> Unit)? = null
    var failNextMessages = 0

    /** Runs right after a message is accepted, before the reply leaves (a test throws here to model a kill). */
    var afterMessageApplied: (() -> Unit)? = null

    /** The server's escrow check (`400 escrow_required` / `400 escrow_forbidden`), as in the Go server. */
    var enforceEscrow = true

    private val revoked = mutableSetOf<String>()

    /** Removes a device from `GET /v1/devices` (a revoked device). Its group membership is the clients' business. */
    fun revokeDevice(accountId: String, deviceId: String) = synchronized(lock) { revoked += "$accountId/$deviceId"; Unit }
    var failNextInboxReads = 0
    var failConversationLookup: ServerException? = null

    /** Runs on every call, before it is served (lock checks, blocking). */
    var onCall: (suspend (identity: String, call: String) -> Unit)? = null

    fun conversation(id: String): Conversation? = synchronized(lock) { conversations[id] }

    fun conversationIds(): List<String> = synchronized(lock) { conversations.keys.sorted() }

    fun pending(identity: String): List<InboxItem> = synchronized(lock) { inboxes[identity].orEmpty().toList() }

    /** Puts an item into a device's inbox directly (a server that lies about the sender, a re-delivery). */
    fun inject(identity: String, make: (cursor: Long) -> InboxItem): InboxItem = synchronized(lock) {
        cursor += 1
        make(cursor).also { inboxes.getOrPut(identity) { mutableListOf() }.add(it) }
    }

    /** Rewrites a device's waiting items (a server that lies about the sender or the conversation). */
    fun rewrite(identity: String, transform: (InboxItem) -> InboxItem) = synchronized(lock) {
        inboxes[identity]?.replaceAll(transform)
    }

    private fun deliver(to: String, kind: String, conversationId: String, epoch: Long, from: String, payload: ByteArray, seq: Long? = null) {
        cursor += 1
        val (account, device) = from.split("/", limit = 2)
        inboxes.getOrPut(to) { mutableListOf() }
            .add(InboxItem(cursor, kind, conversationId, seq, epoch, account, device, payload, Instant.ofEpochMilli(1_700_000_000_000)))
    }

    fun api(accountId: String, deviceId: String): DeliveryApi = Api(accountId, deviceId)

    /** Lands every held commit request, in order. Returns each one's outcome: the new epoch, or the error. */
    fun releaseHeldCommits(): List<Result<Long>> = synchronized(lock) {
        val out = held.map { h -> runCatching { applyCommit(h.identity, h.id, h.epoch, h.commit, h.welcome, h.add, h.remove) } }
        held.clear()
        out
    }

    private fun accountOf(deviceId: String): String =
        keyPackages.entries.firstOrNull { deviceId in it.value }?.key ?: error("unknown device $deviceId")

    /** The server's commit handling. Call with [lock] held. */
    private fun applyCommit(
        identity: String, id: String, epoch: Long, commit: ByteArray, welcome: ByteArray?, addDevices: List<String>, removeDevices: List<String>,
    ): Long {
        commitPosts += Triple(identity, id, epoch)
        val c = conversations[id] ?: throw ServerException(404, "not_found", null, null)
        if (rejectNextCommits > 0) {
            rejectNextCommits -= 1
            throw ServerException(409, "stale_epoch", null, null)
        }
        if (epoch != c.epoch) throw ServerException(409, "stale_epoch", null, null)
        val before = c.members.toList()
        val added = addDevices.map { "${accountOf(it)}/$it" }
        val removed = c.members.filter { m -> removeDevices.any { m.endsWith("/$it") } }
        before.forEach { deliver(it, "commit", id, epoch, identity, commit) }
        c.members.removeAll(removed.toSet())
        c.members.addAll(added)
        c.epoch += 1
        if (welcome != null) added.forEach { deliver(it, "welcome", id, c.epoch, identity, welcome) }
        return c.epoch
    }

    private inner class Api(val account: String, val device: String) : DeliveryApi {
        val identity = "$account/$device"

        private suspend fun call(name: String) {
            synchronized(lock) { calls += "$identity $name" }
            onCall?.invoke(identity, name)
        }

        override suspend fun uploadKeyPackages(keyPackages: List<ByteArray>): Int {
            call("uploadKeyPackages")
            return synchronized(lock) {
                val q = this@FakeDeliveryServer.keyPackages.getOrPut(account) { linkedMapOf() }.getOrPut(device) { ArrayDeque() }
                q.addAll(keyPackages)
                q.size
            }
        }

        override suspend fun keyPackageCount(): Int {
            call("keyPackageCount")
            return synchronized(lock) { keyPackages[account]?.get(device)?.size ?: 0 }
        }

        override suspend fun claimKeyPackages(accountId: String, deviceIds: List<String>?): Map<String, ByteArray> {
            call("claimKeyPackages")
            return synchronized(lock) {
                val out = linkedMapOf<String, ByteArray>()
                keyPackages[accountId].orEmpty().forEach { (d, q) ->
                    if (deviceIds != null && d !in deviceIds) return@forEach
                    q.removeFirstOrNull()?.let { out[d] = it }
                }
                out
            }
        }

        override suspend fun createConversation(peerAccountId: String): ConversationInfo {
            call("createConversation")
            return synchronized(lock) {
                conversationCounter += 1
                val id = "conv-%04d".format(conversationCounter)
                conversations[id] = Conversation(id, defaultMode, 0, java.util.TreeSet(listOf(identity)))
                ConversationInfo(id, defaultMode, 0)
            }
        }

        override suspend fun conversation(id: String): ConversationInfo {
            call("conversation")
            failConversationLookup?.let { throw it }
            return synchronized(lock) {
                val c = conversations[id] ?: throw ServerException(404, "not_found", null, null)
                ConversationInfo(c.id, c.mode, c.epoch)
            }
        }

        override suspend fun postCommit(
            id: String, epoch: Long, commit: ByteArray, welcome: ByteArray?, addDevices: List<String>, removeDevices: List<String>,
        ): Long {
            call("postCommit")
            var lose = false
            val newEpoch = synchronized(lock) {
                if (holdNextCommitRequests > 0) {
                    holdNextCommitRequests -= 1
                    held += HeldCommit(identity, id, epoch, commit, welcome, addDevices, removeDevices)
                    throw ServerException(0, "timeout", null, null)
                }
                val e = applyCommit(identity, id, epoch, commit, welcome, addDevices, removeDevices)
                if (loseNextCommitResponses > 0) { loseNextCommitResponses -= 1; lose = true }
                e
            }
            afterCommitApplied?.invoke()
            if (lose) throw ServerException(0, "network", null, null)
            return newEpoch
        }

        override suspend fun postMessage(id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String): Long {
            call("postMessage")
            val seq = synchronized(lock) {
                if (failNextMessages > 0) {
                    failNextMessages -= 1
                    throw ServerException(503, "unavailable", null, null)
                }
                val c = conversations[id] ?: throw ServerException(404, "not_found", null, null)
                if (enforceEscrow) {
                    if (c.mode == "server_readable" && escrow == null) throw ServerException(400, "escrow_required", null, null)
                    if (c.mode == "e2ee" && escrow != null) throw ServerException(400, "escrow_forbidden", null, null)
                }
                // The client message ID makes a retry idempotent: the same message is stored and delivered once.
                val index = messages.filter { it.conversationId == id }.indexOfFirst { it.clientMsgId == clientMsgId }
                if (index >= 0) return@synchronized (index + 1).toLong()
                if (epoch != c.epoch) throw ServerException(409, "stale_epoch", null, null)
                messages += Posted(id, epoch, ciphertext, escrow, clientMsgId, identity)
                val seq = messages.count { it.conversationId == id }.toLong()
                c.members.filter { it != identity }.forEach { deliver(it, "message", id, epoch, identity, ciphertext, seq) }
                seq
            }
            afterMessageApplied?.invoke()
            return seq
        }

        override suspend fun inbox(after: Long?, limit: Int): InboxPage {
            call("inbox")
            return synchronized(lock) {
                if (failNextInboxReads > 0) {
                    failNextInboxReads -= 1
                    throw ServerException(503, "unavailable", null, null)
                }
                val all = inboxes[identity].orEmpty().filter { after == null || it.cursor > after }.sortedBy { it.cursor }
                InboxPage(all.take(limit), all.size > limit)
            }
        }

        override suspend fun ack(upTo: Long) {
            call("ack")
            synchronized(lock) { inboxes[identity]?.removeAll { it.cursor <= upTo } }
        }

        override suspend fun devices(): List<DeviceRecord> {
            call("devices")
            return synchronized(lock) {
                keyPackages[account].orEmpty().keys.filter { "$account/$it" !in revoked }.map { DeviceRecord(it, it, "android", Instant.EPOCH, null, it == device) }
            }
        }

        override suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef = throw UnsupportedOperationException()
        override suspend fun downloadAttachment(id: String, fromByte: Long): InputStream = throw UnsupportedOperationException()
    }
}
