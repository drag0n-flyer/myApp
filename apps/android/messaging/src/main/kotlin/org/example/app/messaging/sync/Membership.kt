package org.example.app.messaging.sync

import org.example.app.contracts.GroupId
import org.example.app.contracts.MemberKey
import org.example.app.contracts.MlsEngine
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MemberRecord
import org.example.app.messaging.ports.Repository

/**
 * The group's members and the chat's safety number (Design 19, 20), after every processed commit, every
 * `commitAccepted` and every Welcome.
 *
 * Reading the members is an engine call, writing them is database-only. The split lets the inbox processor read
 * under the MLS lock and write in the transaction that also marks the item processed: [keys] first, then [apply].
 * [update] does both. Callers hold the MLS lock around either.
 *
 * D5 seam: `diff()` (device-added/removed lines, the safety-number-changed line and the own-device notice) goes
 * into [apply], which already compares against the stored rows.
 */
class Membership(
    private val me: LocalDevice,
    private val mls: MlsEngine,
    /** `LiveMlsEngine.providesRealMemberKeys`. False until MLS M7; the safety-number UI stays hidden while false. */
    val realMemberKeys: Boolean,
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

    suspend fun update(conversation: ConversationRecord, repo: Repository) {
        val keys = keys(GroupId(conversation.groupId))
        repo.transaction { apply(keys, conversation, repo) }
    }

    /** Database-only half; run it inside the caller's transaction. */
    fun apply(keys: List<MemberKey>, conversation: ConversationRecord, repo: Repository) = write(me, keys, conversation, repo)

    companion object {
        /** [apply] for code that has no engine (`IncomingHandler`). */
        fun write(me: LocalDevice, keys: List<MemberKey>, conversation: ConversationRecord, repo: Repository) {
            val current = repo.conversation(conversation.serverId) ?: conversation
            val fresh = keys.map { MemberRecord(it.identity, it.signatureKey) }
            if (repo.members(current.serverId) != fresh) repo.replaceMembers(current.serverId, fresh)

            // The chat's number follows its preferred conversation. An oversized identity or key gives no number.
            if (repo.preferredConversation(current.chatId)?.serverId == current.serverId) {
                val number = runCatching { SafetyNumber.compute(keys, me.accountId, current.chatId).joinToString("") }.getOrNull()
                if (number != null && repo.safety(current.chatId)?.current != number) repo.setSafetyCurrent(current.chatId, number)
            }
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
