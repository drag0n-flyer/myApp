package org.example.app.messaging.sync

import kotlinx.coroutines.CancellationException
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.ServerException
import org.example.app.core.chats.GroupChangeFailed
import org.example.app.core.chats.GroupChangeFailed.Reason
import org.example.app.core.groups.GroupNameRule
import org.example.app.core.groups.GroupRole
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ChatKind
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.ports.GroupStoreRules
import org.example.app.messaging.ports.MyState
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.send.Outbox

/**
 * What a person does to a group: add, remove, leave, set a role (and hand over ownership), rename, change the photo.
 * The server call comes first, because the server is the authority and refuses what the roles do not allow; the
 * member list, the lines and the MLS commits then follow through [GroupSync.reconcile], the same path every other
 * member's device takes. This account's own role is only a pre-check (it saves a call and names the reason); the
 * server's answer decides. Every failure is a [GroupChangeFailed] and leaves this device's rows as they were.
 *
 * A change the server accepted but whose commit could not be made yet is not an error: the hint stays marked and the
 * next start-up, reconnect or `members` item finishes it.
 */
class GroupAdmin(
    private val me: LocalDevice,
    private val server: DeliveryApi,
    private val repo: Repository,
    private val clock: Clock,
    private val sync: GroupSync,
    private val outbox: Outbox,
) {
    private class Target(val chat: ChatRecord, val conversationId: String, val role: GroupRole, val members: List<GroupMemberRecord>)

    private fun target(chatId: String): Target {
        val chat = repo.chat(chatId)?.takeIf { it.kind == ChatKind.GROUP } ?: throw GroupChangeFailed(Reason.NotFound)
        val conversationId = chat.conversationId ?: throw GroupChangeFailed(Reason.NotFound)
        // Read-only after leaving or being removed; a group still being created has nothing to change yet.
        if (chat.myState != MyState.MEMBER) throw GroupChangeFailed(Reason.Forbidden)
        if (repo.preferredConversation(chatId) == null) throw GroupChangeFailed(Reason.Other)
        val members = repo.groupMembers(chatId)
        val role = members.firstOrNull { it.accountId == me.accountId }?.let { GroupRole.from(it.role) } ?: throw GroupChangeFailed(Reason.Forbidden)
        return Target(chat, conversationId, role, members)
    }

    private suspend fun <T> serverCall(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServerException) {
        throw GroupChangeFailed(
            when (e.code) {
                "owner_must_transfer" -> Reason.OwnerMustTransfer
                "forbidden" -> Reason.Forbidden
                "group_full" -> Reason.Full
                "not_found" -> Reason.NotFound
                else -> Reason.Other
            },
            e,
        )
    } catch (e: Exception) {
        throw GroupChangeFailed(Reason.Other, e)
    }

    /** The follow-up after the server accepted a change: null when it could not finish (it is retried later). */
    private suspend fun follow(conversationId: String): GroupSync.Result? = try {
        sync.reconcile(conversationId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Returns the accounts that are not in the group yet: skipped by the server, or with no device ready. */
    suspend fun addMembers(chatId: String, accounts: List<String>): List<String> {
        val t = target(chatId)
        if (!t.role.canAdd) throw GroupChangeFailed(Reason.Forbidden)
        val have = t.members.map { it.accountId }.toSet()
        val fresh = accounts.filter { it.isNotEmpty() && it != me.accountId && it !in have }.distinct()
        if (fresh.isEmpty()) return emptyList()
        if (have.size + fresh.size > GroupStoreRules.MAX_MEMBERS) throw GroupChangeFailed(Reason.Full)
        sync.hint(t.conversationId, serverCall { server.addAccounts(t.conversationId, fresh) }.membersVersion, me.accountId)
        val result = follow(t.conversationId) ?: return fresh
        val listed = repo.groupMembers(chatId).map { it.accountId }.toSet()
        return fresh.filter { it !in listed || it in result.notAddedYet }
    }

    suspend fun removeMember(chatId: String, account: String) {
        val t = target(chatId)
        val targetRole = t.members.firstOrNull { it.accountId == account }?.let { GroupRole.from(it.role) } ?: throw GroupChangeFailed(Reason.NotFound)
        if (account == me.accountId || !t.role.canRemove(targetRole)) throw GroupChangeFailed(Reason.Forbidden)
        sync.hint(t.conversationId, serverCall { server.removeAccount(t.conversationId, account) }.membersVersion, me.accountId)
        follow(t.conversationId)
    }

    /** The owner must hand the group over first, unless nobody else is in it. */
    suspend fun leave(chatId: String) {
        val t = target(chatId)
        if (t.role == GroupRole.Owner && t.members.size > 1) throw GroupChangeFailed(Reason.OwnerMustTransfer)
        serverCall { server.removeAccount(t.conversationId, me.accountId) }
        sync.leftByMe(t.chat)
    }

    /** Only the owner. Setting someone else to owner hands the group over (the server makes this account an admin). */
    suspend fun setRole(chatId: String, account: String, role: GroupRole) {
        val t = target(chatId)
        if (!t.role.canSetRoles || account == me.accountId) throw GroupChangeFailed(Reason.Forbidden)
        if (t.members.none { it.accountId == account }) throw GroupChangeFailed(Reason.NotFound)
        sync.hint(t.conversationId, serverCall { server.setRole(t.conversationId, account, role.wire) }.membersVersion, me.accountId)
        follow(t.conversationId)
    }

    suspend fun rename(chatId: String, name: String) {
        val t = target(chatId)
        if (!t.role.canEditInfo) throw GroupChangeFailed(Reason.Forbidden)
        val title = GroupNameRule.clean(name) ?: throw GroupChangeFailed(Reason.Other)
        changeInfo(t, title, keepPhoto = true, photo = null)
    }

    /** A JPEG that passes the profile photo rule, or null for none. */
    suspend fun setPhoto(chatId: String, photo: ByteArray?) {
        val t = target(chatId)
        if (!t.role.canEditInfo) throw GroupChangeFailed(Reason.Forbidden)
        val title = t.chat.title ?: throw GroupChangeFailed(Reason.Other)
        try {
            GroupStoreRules.requireInfo(title, photo)
        } catch (e: IllegalArgumentException) {
            throw GroupChangeFailed(Reason.Other, e)
        }
        changeInfo(t, title, keepPhoto = false, photo = photo)
    }

    /**
     * The new name and photo at `ver + 1`, the `group` envelope and the chat line, in ONE transaction: the stored
     * version is read inside it, so a higher version that arrived a moment ago is built upon, never overwritten.
     */
    private fun changeInfo(t: Target, title: String, keepPhoto: Boolean, photo: ByteArray?) {
        try {
            repo.transaction {
                val chat = repo.chat(t.chat.id) ?: throw GroupChangeFailed(Reason.NotFound)
                val newPhoto = if (keepPhoto) chat.photo else photo
                val ver = (chat.groupVer ?: 0) + 1
                if (!repo.setGroupInfo(chat.id, title, newPhoto, ver)) throw GroupChangeFailed(Reason.Other)
                outbox.enqueueControl(chat.id) { id, at -> ContentEnvelope.Group(id, at, title, newPhoto, ver) }
                val now = clock.now()
                if (chat.title != title) {
                    GroupState.insertLine(repo, chat.id, chat.conversationId, "sys-group-${chat.id}-$ver-name", now, GroupState.envelope("groupRenamed", mapOf("name" to title)))
                }
                if (!(chat.photo contentEquals newPhoto)) {
                    GroupState.insertLine(repo, chat.id, chat.conversationId, "sys-group-${chat.id}-$ver-photo", now + 1, GroupState.envelope("groupPhoto", emptyMap()))
                }
            }
        } catch (e: GroupChangeFailed) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw GroupChangeFailed(Reason.Other, e)
        }
    }
}
