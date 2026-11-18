package org.example.app.messaging.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.GroupId
import org.example.app.contracts.MembersEvent
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.ServerException
import org.example.app.core.groups.GroupRole
import org.example.app.core.groups.Member
import org.example.app.core.groups.MemberDiff
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ChatKind
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.ports.GroupStoreRules
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.MyState
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.send.Outbox

/**
 * Keeps this device's copy of every group's member list, its lines and its MLS members in step with the server, which
 * is the authority for who is in a group and who is an admin.
 *
 * A `members` inbox item is only a hint that something changed ([note]); the list and its version are always read
 * from `GET /v1/conversations/{id}` ([reconcile]), so a missed item, a jump of several versions or a lying item cannot
 * leave a wrong list: [setGroupMembers][Repository.setGroupMembers] ignores a version that is not higher. The hint is
 * kept as a `kv` marker before anything else happens, and cleared only after the reconcile worked. At every start-up
 * and reconnect every group this account is in is reconciled ([noteAll]). A `members` item for a conversation without
 * a chat here, and a server group without a local chat, are ignored (a creator's server group can be orphaned).
 *
 * After the list is stored: devices of accounts that are not in the list are removed from the MLS group in one commit
 * (any member may; the first commit per epoch wins, a rejected one is rebuilt on the new epoch and finds nothing left
 * to do), and a listed account none of whose devices is in the group is added when it has KeyPackages (the next client
 * that sees it missing does it). Neither runs inside the inbox fetch: the commit reads the inbox itself.
 */
class GroupSync(
    private val me: LocalDevice,
    private val mls: MlsEngine,
    private val server: DeliveryApi,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
    private val conversations: ConversationManager,
    private val outbox: Outbox,
    private val syncOnce: suspend () -> Unit,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val running = Mutex()

    /** The account the server named in the latest `members` item per conversation, with that item's version (a hint for lines). */
    private val actors = ConcurrentHashMap<String, Pair<Long, String>>()

    private class AlreadyDone : Exception()

    /** What one [reconcile] left: accounts in the group's list that have no device in the MLS group yet. */
    class Result(val notAddedYet: List<String>)

    private fun memberChat(conversationId: String): ChatRecord? =
        repo.chats().firstOrNull { it.kind == ChatKind.GROUP && it.conversationId == conversationId && it.myState == MyState.MEMBER }

    /** Stores the hint durably. False (nothing stored) for a conversation this device has no member chat for. */
    fun note(event: MembersEvent): Boolean {
        if (memberChat(event.conversationId) == null) return false
        event.actor?.let { actors[event.conversationId] = event.membersVersion to it }
        mark(event.conversationId)
        return true
    }

    /** This account's own change just made on the server: its version is [version], and the actor is known to be this account. */
    fun hint(conversationId: String, version: Long, actor: String) {
        actors[conversationId] = version to actor
    }

    /** Every group this account is in: start-up and reconnect. */
    fun noteAll() {
        for (chat in repo.chats()) {
            if (chat.kind == ChatKind.GROUP && chat.myState == MyState.MEMBER) chat.conversationId?.let(::mark)
        }
    }

    private fun mark(conversationId: String) = repo.set(PENDING_PREFIX + conversationId, System.nanoTime().toString().encodeToByteArray())

    /** Reconciles every marked group. Each is tried even when another fails; the first error is thrown at the end. */
    suspend fun runPending() = running.withLock {
        var failure: Exception? = null
        for ((key, value) in repo.entries(PENDING_PREFIX)) {
            if (value.isEmpty()) continue
            try {
                reconcileLocked(key.removePrefix(PENDING_PREFIX))
                // A hint that came in meanwhile has a new value and stays.
                if (repo.get(key)?.contentEquals(value) == true) repo.set(key, ByteArray(0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }

    /** One group now, for a caller that has just changed it on the server. Clears its marker on success. */
    suspend fun reconcile(conversationId: String): Result = running.withLock {
        mark(conversationId)
        val value = repo.get(PENDING_PREFIX + conversationId)
        reconcileLocked(conversationId).also {
            if (repo.get(PENDING_PREFIX + conversationId)?.contentEquals(value) == true) repo.set(PENDING_PREFIX + conversationId, ByteArray(0))
        }
    }

    private suspend fun reconcileLocked(conversationId: String): Result {
        val chat = memberChat(conversationId) ?: return Result(emptyList())
        // A group still being created (or never finished) has no active conversation: nothing to follow yet.
        val conversation = repo.preferredConversation(chat.id)
        if (conversation == null || PendingCreate.isPending(repo, conversation.serverId)) return Result(emptyList())
        val info = try {
            server.conversation(conversationId)
        } catch (e: ServerException) {
            if (e.status == 404) {
                // The server says this account is not in the conversation (or it is gone).
                gone(chat, actors[conversationId]?.second)
                return Result(emptyList())
            }
            throw e
        }
        val members = validated(info, conversationId) ?: return Result(emptyList()).also { log("group_list_invalid conversation=$conversationId") }
        val version = info.membersVersion ?: return Result(emptyList())
        if (members.none { it.accountId == me.accountId }) {
            gone(chat, actors[conversationId]?.second)
            return Result(emptyList())
        }
        applyList(chat, members, version, actors[conversationId])
        return Result(syncDevices(chat, conversation, members))
    }

    /** The server's list when it can be true: a group, this conversation, a version, at most 100 distinct known-role members. */
    private fun validated(info: ConversationInfo, conversationId: String): List<GroupMemberRecord>? {
        if (info.kind != "group" || info.conversationId != conversationId) return null
        val version = info.membersVersion ?: return null
        val members = info.members.map { GroupMemberRecord(it.account, it.role) }
        return try {
            GroupStoreRules.requireMembers(members, version)
            members
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Stores the list and writes the lines for the difference, once: a version that is not higher changes nothing. */
    private fun applyList(chat: ChatRecord, members: List<GroupMemberRecord>, version: Long, hint: Pair<Long, String>?) {
        repo.transaction {
            val stored = repo.chat(chat.id)?.membersVersion
            // The actor is the server's claim for one step only: it names the account in the lines, nothing else.
            val actor = hint?.takeIf { stored != null && version == stored + 1 && it.first == version }?.second
            val old = repo.setGroupMembers(chat.id, members, version) ?: return@transaction
            if (old.isEmpty()) return@transaction
            val lines = MemberDiff.lines(old.map(::member), members.map(::member), actor, me.accountId)
            val at = clock.now()
            lines.forEachIndexed { index, line ->
                val envelope = GroupState.lineFor(line, me.accountId, actor) ?: return@forEachIndexed
                GroupState.insertLine(repo, chat.id, chat.conversationId, GroupState.lineId(chat.conversationId.orEmpty(), version, index), at + index, envelope)
            }
        }
    }

    private fun member(r: GroupMemberRecord) = Member(r.accountId, GroupRole.from(r.role) ?: GroupRole.Member)

    /** No longer a member: left (the server named this account as the actor) or removed. */
    private suspend fun gone(chat: ChatRecord, actor: String?) {
        val left = actor == me.accountId
        conversations.critical {
            repo.transaction { GroupState.markGone(repo, clock, chat, if (left) MyState.LEFT else MyState.REMOVED, line = true) }
        }
        chat.conversationId?.let { actors.remove(it) }
    }

    /** Called by this device's own leave. */
    suspend fun leftByMe(chat: ChatRecord) = gone(chat, me.accountId)

    // MLS members

    private suspend fun identities(group: GroupId): List<String> = lock.withLock { mls.members(group) }

    private suspend fun syncDevices(chat: ChatRecord, conversation: ConversationRecord, members: List<GroupMemberRecord>): List<String> {
        val group = GroupId(conversation.groupId)
        val accounts = members.map { it.accountId }.toSet()
        var inGroup = identities(group)
        val stale = inGroup.filter { it.substringBefore('/') !in accounts }.sorted()
        if (stale.isNotEmpty()) {
            removeStale(conversation, group, stale, accounts)
            inGroup = identities(group)
        }
        val present = inGroup.map { it.substringBefore('/') }.toSet()
        val missing = accounts.filter { it != me.accountId && it !in present }.sorted()
        if (missing.isEmpty()) return emptyList()
        // Only an owner or admin claims KeyPackages and adds devices; a plain member still removes departed devices.
        val myRole = members.firstOrNull { it.accountId == me.accountId }?.let { GroupRole.from(it.role) }
        return if (myRole?.canAdd == true) addMissing(chat, conversation, group, missing) else missing
    }

    private suspend fun removeStale(conversation: ConversationRecord, group: GroupId, stale: List<String>, accounts: Set<String>) {
        try {
            // The server took these devices out of delivery when it removed their account, and refuses a commit that names a
            // device that is no longer in the conversation: `removeDevices` stays empty, the commit itself removes them from MLS.
            conversations.postCommitWithRetry(conversation.serverId, group, emptyList(), emptyList()) {
                // Under the lock; after a stale epoch the inbox has been read and another member may have done it.
                val still = mls.members(group).filter { it in stale && it.substringBefore('/') !in accounts }
                if (still.isEmpty()) throw AlreadyDone()
                mls.remove(still, group)
            }
        } catch (_: AlreadyDone) {
            // Nothing left to remove.
        }
    }

    /** Claims KeyPackages for [missing] accounts and adds their devices in one commit. Returns the accounts still without a device. */
    private suspend fun addMissing(chat: ChatRecord, conversation: ConversationRecord, group: GroupId, missing: List<String>): List<String> {
        val claimed = LinkedHashMap<String, ByteArray>()
        val owner = HashMap<String, String>()
        for (account in missing) {
            val packages = try {
                server.claimKeyPackages(account)
            } catch (e: ServerException) {
                if (e.status != 404) throw e
                emptyMap()
            }
            packages.forEach { (device, kp) -> claimed[device] = kp; owner[device] = account }
        }
        if (claimed.isEmpty()) return missing
        val modeKnown = repo.conversation(conversation.serverId)?.innerMode != null
        if (modeKnown) ModePin.setPending(repo, conversation.serverId, true)
        var failure: Exception? = null
        var used: List<String> = claimed.keys.toList()
        try {
            conversations.postCommitWithRetry(conversation.serverId, group, used, emptyList(), addedBy = { used }) {
                val present = mls.members(group).map { it.substringBefore('/') }.toSet()
                val use = claimed.filter { owner.getValue(it.key) !in present }
                if (use.isEmpty()) throw AlreadyDone()
                used = use.keys.toList()
                mls.add(use.values.toList(), group)
            }
        } catch (_: AlreadyDone) {
            return emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e // the commit may have merged: what the new devices need is still sent below
        }
        // The new devices have no name, photo or mode yet: say them again, after the commit (a newer epoch they can read).
        announce(chat, conversation.serverId)?.let { failure?.addSuppressed(it) ?: run { failure = it } }
        failure?.let { throw it }
        return missing.filter { it !in owner.values.toSet() }
    }

    private suspend fun announce(chat: ChatRecord, serverId: String): Exception? {
        try {
            repo.chat(chat.id)?.let { c ->
                val title = c.title
                // Receivers keep a `group` envelope only from an owner or admin: a plain member's would be ignored.
                val role = repo.groupMembers(c.id).firstOrNull { it.accountId == me.accountId }?.let { GroupRole.from(it.role) }
                if (title != null && role?.canEditInfo == true) outbox.enqueueControl(c.id) { id, at -> ContentEnvelope.Group(id, at, title, c.photo, c.groupVer ?: 1) }
            }
            val fresh = repo.conversation(serverId)?.takeIf { it.innerMode != null }
            if (fresh != null) {
                ModePin.clearAnnounce(repo, fresh.serverId)
                conversations.announceMode(fresh)
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e
        }
    }

    companion object {
        /** `kv` marker per conversation: a `members` hint (or a start-up) says its list must be read again. Empty = done. */
        const val PENDING_PREFIX = "group-sync:"
    }
}
