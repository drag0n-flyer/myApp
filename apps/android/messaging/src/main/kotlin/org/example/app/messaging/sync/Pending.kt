package org.example.app.messaging.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.example.app.messaging.ports.RawInboxRecord
import org.example.app.messaging.ports.Repository

/**
 * A conversation this device created whose add commit is not settled yet (`kv` row `create-pending:<serverId>`).
 *
 * The conversation row is written, inactive and bound to its group, before the commit is posted, together with
 * this record of the exact commit, Welcome and added devices. A lost reply, a timeout or a kill then leaves
 * something to finish: the echo activates the row, or `ConversationManager` confirms it against the server's
 * epoch, or posts the same commit again. Cleared when the conversation becomes active or the server refuses it.
 */
class PendingCreate(val commit: ByteArray, val welcome: ByteArray?, val addDevices: List<String>) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(commit.size); d.write(commit)
            d.writeInt(welcome?.size ?: -1); welcome?.let { d.write(it) }
            d.writeInt(addDevices.size); addDevices.forEach { d.writeUTF(it) }
        }
        return out.toByteArray()
    }

    companion object {
        const val PREFIX = "create-pending:"

        fun key(serverId: String) = PREFIX + serverId

        fun save(repo: Repository, serverId: String, pending: PendingCreate) = repo.set(key(serverId), pending.encode())

        /** Null when there is none (an empty value is a cleared one). */
        fun load(repo: Repository, serverId: String): PendingCreate? = repo.get(key(serverId))?.takeIf { it.isNotEmpty() }?.let(::decode)

        fun isPending(repo: Repository, serverId: String): Boolean = repo.get(key(serverId))?.isNotEmpty() == true

        fun clear(repo: Repository, serverId: String) = repo.set(key(serverId), ByteArray(0))

        /** Server IDs of every pending create. */
        fun all(repo: Repository): List<String> =
            repo.entries(PREFIX).filterValues { it.isNotEmpty() }.keys.map { it.removePrefix(PREFIX) }.sorted()

        private fun decode(bytes: ByteArray): PendingCreate = DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val commit = ByteArray(d.readInt()).also { d.readFully(it) }
            val welcomeSize = d.readInt()
            val welcome = if (welcomeSize < 0) null else ByteArray(welcomeSize).also { d.readFully(it) }
            PendingCreate(commit, welcome, List(d.readInt()) { d.readUTF() })
        }
    }
}

/**
 * An inbox item that could not be applied and must not block the queue: a Welcome whose chat could not be set up,
 * or an item for a group with no conversation. It is kept (`kv` row `parked:<cursor>`: the reason and the raw
 * item) instead of being dropped, so a later version or a repair pass can look at it. Never holds plaintext.
 */
object Parked {
    const val PREFIX = "parked:"

    fun save(repo: Repository, raw: RawInboxRecord, reason: String) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeUTF(reason); d.writeLong(raw.cursor); d.writeUTF(raw.kind); d.writeUTF(raw.conversationId)
            d.writeLong(raw.seq ?: -1); d.writeLong(raw.epoch); d.writeUTF(raw.senderAccount); d.writeUTF(raw.senderDevice)
            d.writeLong(raw.sentAt); d.writeInt(raw.payload.size); d.write(raw.payload)
        }
        repo.set(PREFIX + raw.cursor, out.toByteArray())
    }
}
