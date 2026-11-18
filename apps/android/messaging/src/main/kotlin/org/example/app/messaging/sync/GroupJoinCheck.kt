package org.example.app.messaging.sync

import org.example.app.contracts.ConversationInfo
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.ports.GroupStoreRules
import org.example.app.messaging.ports.Repository

/**
 * Whether a welcome for a conversation the server calls a group is joined. Decided before the MLS state is touched, from
 * the server's answer alone, which is the authority for who is in a group: a list that cannot be true, a group this
 * account is not in, and a conversation that already has a chat here are all dropped.
 */
internal object GroupJoinCheck {
    sealed interface Decision {
        class Join(val join: ConversationManager.GroupJoin) : Decision

        /** Dropped; [park] keeps the item and logs this reason (a list that cannot be true), null drops it silently. */
        class Drop(val park: String?) : Decision
    }

    fun decide(info: ConversationInfo, me: String, claimedSender: String, repo: Repository): Decision {
        val members = info.members.map { GroupMemberRecord(it.account, it.role) }
        try {
            GroupStoreRules.requireMembers(members, info.membersVersion ?: -1)
        } catch (_: IllegalArgumentException) {
            return Decision.Drop("group_list_invalid")
        }
        if (members.none { it.accountId == me }) return Decision.Drop(null)
        if (repo.chats().any { it.conversationId == info.conversationId }) return Decision.Drop(null)
        // The sender of a welcome is the server's claim: it is named in the line only when it is a listed member.
        val addedBy = claimedSender.takeIf { s -> s != me && members.any { it.accountId == s } }
        return Decision.Join(ConversationManager.GroupJoin(members, info.membersVersion ?: 0, addedBy))
    }
}
