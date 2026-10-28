package org.example.app.messaging.sync

import org.example.app.contracts.GroupId
import org.example.app.contracts.MemberKey
import org.example.app.contracts.MlsEngine
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MemberRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.Repository

/**
 * What a membership update changed, as full identities (`"<accountId>/<deviceId>"`) except [ownDevicesAdded], which
 * holds the device IDs of my own account (never this device).
 */
data class MembershipChange(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val ownDevicesAdded: List<String> = emptyList(),
)

/**
 * The group's members and the chat's safety number (Design 19, 20), after every processed commit, every
 * `commitAccepted` and every Welcome.
 *
 * Reading the members is an engine call, writing them is database-only. The split lets the inbox processor read
 * under the MLS lock and write in the transaction that also marks the item processed: [keys] first, then [apply].
 * [update] does both. Callers hold the MLS lock around either.
 *
 * [diff] compares the stored rows with the group's members. [apply] writes the device-added and device-removed
 * lines, the safety-number-changed line and the new members in the caller's transaction, and returns the change; the
 * caller turns [MembershipChange.ownDevicesAdded] into the own-device notice (`IncomingHandler`).
 */
class Membership(
    private val me: LocalDevice,
    private val mls: MlsEngine,
    /** `LiveMlsEngine.providesRealMemberKeys`. False until MLS M7; the safety-number UI stays hidden while false. */
    val realMemberKeys: Boolean,
    private val clock: Clock = Clock.System,
) {
    /**
     * The members with their signature keys, sorted by identity (UTF-8 bytes).
     *
     * M7 seam: until the engine exports real keys this uses `members()` with placeholder keys
     * (`"fake-sig:<identity>"`, what `FakeMlsEngine` and today's `LiveMlsEngine` return), like the iPhone, so the
     * lines and the number's shape work but the number does not yet cover the real keys.
     */
    suspend fun keys(group: GroupId): List<MemberKey> {
        val keys = if (realMemberKeys) mls.memberKeys(group)
        else mls.members(group).map { MemberKey(it, "fake-sig:$it".encodeToByteArray()) }
        return keys.sortedWith { a, b -> compareUtf8(a.identity, b.identity) }
    }

    /** A failed read flags the conversation ([markUnreadable]) and still throws. */
    suspend fun update(conversation: ConversationRecord, repo: Repository): MembershipChange {
        val keys = try {
            keys(GroupId(conversation.groupId))
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) markUnreadable(conversation.serverId, repo)
            throw e
        }
        return repo.transaction { apply(keys, conversation, repo) }
    }

    /** Database-only half; run it inside the caller's transaction. */
    fun apply(keys: List<MemberKey>, conversation: ConversationRecord, repo: Repository): MembershipChange =
        write(me, keys, conversation, repo, clock.now())

    companion object {
        /**
         * A `kv` flag per conversation: the engine could not read its members (a member's credential it cannot read,
         * or any other failure), so its device lines and safety number may be out of date. Set instead of skipping
         * silently; cleared by the next [write]. While set, the chat shows no safety number and says why.
         */
        const val UNREADABLE_PREFIX = "members.unreadable."

        /** Flags a known conversation; an unknown one is ignored. */
        fun markUnreadable(conversationId: String, repo: Repository) {
            if (repo.conversation(conversationId) == null) return
            repo.set(UNREADABLE_PREFIX + conversationId, byteArrayOf(1))
        }

        fun isUnreadable(conversationId: String, repo: Repository): Boolean =
            repo.get(UNREADABLE_PREFIX + conversationId)?.contentEquals(byteArrayOf(1)) == true

        /** True when the members of any active conversation of the chat could not be read. */
        fun membersUnreadable(chatId: String, repo: Repository): Boolean =
            repo.conversations(chatId).any { it.active && isUnreadable(it.serverId, repo) }

        /** [apply] for code that has no engine (`IncomingHandler`). [now] stamps the device lines. */
        fun write(me: LocalDevice, keys: List<MemberKey>, conversation: ConversationRecord, repo: Repository, now: Long): MembershipChange {
            // The epoch in the line IDs is the stored one: callers store the new epoch first.
            val current = repo.conversation(conversation.serverId) ?: conversation
            val stored = repo.members(current.serverId)
            // The members were read: any earlier unreadable flag no longer applies.
            if (isUnreadable(current.serverId, repo)) repo.set(UNREADABLE_PREFIX + current.serverId, ByteArray(0))
            val lines = mutableListOf<Pair<String, ByteArray>>()

            // No stored rows yet: the commit or Welcome that creates the conversation. Store, no lines.
            val change = if (stored.isEmpty()) MembershipChange() else diff(me, stored, keys)
            fun name(identity: String) = identity.substringBefore('/').takeIf { it != me.accountId }
            change.added.forEach { lines += "add-$it" to lineEnvelope("deviceAdded", name(it)) }
            change.removed.forEach { lines += "remove-$it" to lineEnvelope("deviceRemoved", name(it)) }

            val fresh = keys.map { MemberRecord(it.identity, it.signatureKey) }
            if (stored != fresh) repo.replaceMembers(current.serverId, fresh)

            // The chat's number follows its preferred conversation. An oversized identity or key gives no number.
            if (repo.preferredConversation(current.chatId)?.serverId == current.serverId) {
                val number = runCatching { SafetyNumber.compute(keys, me.accountId, current.chatId).joinToString("") }.getOrNull()
                val previous = repo.safety(current.chatId)?.current
                if (number != null && previous != number) {
                    repo.setSafetyCurrent(current.chatId, number)
                    if (previous != null) lines += "safety" to lineEnvelope("safetyNumberChanged", null)
                }
            }

            // One millisecond apart, so the lines keep their order.
            lines.forEachIndexed { index, (id, envelope) ->
                val at = now + index
                repo.insertMessage(
                    MessageRecord(
                        id = "sys-${current.serverId}-${current.epoch}-$id", chatId = current.chatId, conversationId = current.serverId,
                        senderAccount = "system", senderDevice = null, sentAt = at, receivedAt = at, envelope = envelope, status = "sent",
                    ),
                )
            }
            return change
        }

        /** Devices that joined or left, comparing the stored rows with [keys]. This device is never reported. */
        fun diff(me: LocalDevice, stored: List<MemberRecord>, keys: List<MemberKey>): MembershipChange {
            val old = stored.map { it.identity }.toSet()
            val new = keys.map { it.identity }.toSet()
            val added = (new - old).filter { it != me.identity }.sortedWith { a, b -> compareUtf8(a, b) }
            val removed = (old - new).filter { it != me.identity }.sortedWith { a, b -> compareUtf8(a, b) }
            val prefix = me.accountId + "/"
            return MembershipChange(added, removed, added.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) })
        }

        /**
         * The device line as the iPhone's `SystemEvent` Codable writes it: `{"deviceAdded":{"name":"<account>"}}`
         * (no name for my own account), `{"safetyNumberChanged":{}}`. Names are account IDs; the chat resolves them
         * when the line is shown, they are never stored as display names.
         */
        fun lineEnvelope(kind: String, name: String?): ByteArray {
            val escaped = name?.let { "\"name\":" + kotlinx.serialization.json.JsonPrimitive(it) }.orEmpty()
            return "{\"$kind\":{$escaped}}".encodeToByteArray()
        }

        private fun compareUtf8(a: String, b: String): Int {
            val x = a.encodeToByteArray(); val y = b.encodeToByteArray()
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return x.size - y.size
        }
    }
}
