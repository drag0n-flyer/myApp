package org.example.app.messaging.sync

import kotlinx.coroutines.sync.withLock
import org.example.app.contracts.GroupId
import org.example.app.contracts.ServerException
import org.example.app.core.groups.ChatID
import org.example.app.core.groups.GroupNameRule
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.ports.GroupStoreRules
import org.example.app.messaging.ports.Repository
import org.example.app.messaging.send.Outbox

/** What [GroupCreator.create] made. [notAdded] are accounts that are not in the group yet (no KeyPackage, or the server skipped them). */
class GroupCreation(val chatId: String, val notAdded: List<String>)

/**
 * Creates a group chat: one MLS group whose members are the devices of every member account, exactly like a one-to-one
 * chat, with the server holding the account list. The create reuses [ConversationManager]'s pending-create path (the
 * commit is stored before it is posted, and a lost reply is finished at the next start), so a group is never made twice.
 *
 * @param ownProfile this account's `profile` envelope, sent after the group envelope; null while there is none.
 */
class GroupCreator(
    private val manager: ConversationManager,
    private val repo: Repository,
    private val outbox: Outbox,
    private val ownProfile: (id: String, at: Long) -> ContentEnvelope.Profile? = { _, _ -> null },
) {
    /**
     * @param accounts the other people: 2 to 99, distinct, never this account.
     * @throws IllegalArgumentException for a list, name or photo that breaks the rules (before any network call).
     * @throws SyncException.NoKeyPackages when nobody could be added.
     * @throws ServerException `not_found`, `group_full` and the rest as the server answers.
     */
    suspend fun create(accounts: List<String>, name: String, photo: ByteArray?): GroupCreation {
        val me = manager.localAccountId
        require(accounts.size in MIN_OTHERS..MAX_OTHERS) { "Between 2 and 99 other people" }
        require(accounts.toSet().size == accounts.size && me !in accounts && accounts.none { it.isEmpty() }) { "Distinct other accounts" }
        val title = requireNotNull(GroupNameRule.clean(name)) { "Bad group name" }
        GroupStoreRules.requireInfo(title, photo)
        return manager.creating.withLock { createLocked(accounts, title, photo) }
    }

    private suspend fun createLocked(accounts: List<String>, title: String, photo: ByteArray?): GroupCreation {
        val server = manager.server
        val me = manager.localAccountId
        // 1. KeyPackages for every device of every member and for my other devices. A member with none is left out.
        val claimed = LinkedHashMap<String, ByteArray>() // device ID to package
        val deviceAccount = HashMap<String, String>()
        val withoutPackages = mutableListOf<String>()
        for (account in accounts) {
            val packages = try {
                server.claimKeyPackages(account)
            } catch (e: ServerException) {
                if (e.status != 404) throw e
                emptyMap()
            }
            if (packages.isEmpty()) withoutPackages += account
            packages.forEach { (device, kp) -> claimed[device] = kp; deviceAccount[device] = account }
        }
        val reachable = accounts - withoutPackages.toSet()
        if (reachable.isEmpty()) throw SyncException.NoKeyPackages
        // My other devices, by ID: this device's own KeyPackage is never claimed (and wasted).
        val otherOwn = server.devices().map { it.id }.filter { it != manager.localDeviceId }
        val mine = if (otherOwn.isEmpty()) emptyMap() else server.claimKeyPackages(me, otherOwn).filterKeys { it in otherOwn }
        mine.forEach { (device, kp) -> claimed[device] = kp; deviceAccount[device] = me }

        // 2. The server conversation, then its member list (an account that blocked me is silently absent).
        val created = server.createGroup(reachable)
        val info = server.conversation(created.conversationId)
        if (info.kind != "group") throw SyncException.ConversationRebound
        val members = info.members.map { GroupMemberRecord(it.account, it.role) }
        GroupStoreRules.requireMembers(members, info.membersVersion ?: 0)
        val listed = members.map { it.accountId }.toSet()
        val skipped = reachable.filter { it !in listed }
        val devices = claimed.filterKeys { deviceAccount[it] in listed }

        // 3. The MLS group and its add commit, then the rows (inactive conversation, pending commit) in one transaction.
        val chatId = ChatID.group(created.conversationId)
        manager.critical {
            val group = manager.mls.createGroup()
            val bundle = manager.mls.add(devices.values.toList(), group)
            val record = ConversationRecord(
                serverId = created.conversationId, chatId = chatId, groupId = group.raw, mode = created.mode,
                innerMode = created.mode, modeMismatch = false, epoch = 0, active = false, createdAt = manager.clock.now(),
            )
            repo.transaction {
                repo.upsertGroup(chatId, created.conversationId, title, manager.clock.now())
                repo.setGroupInfo(chatId, title, photo, 1)
                repo.setGroupMembers(chatId, members, info.membersVersion ?: 0)
                ModePin.setPending(repo, record.serverId, true)
                repo.set(INIT_PREFIX + record.serverId, byteArrayOf(1)) // the group and profile envelopes still to queue
                repo.upsertConversation(record)
                PendingCreate.save(repo, record.serverId, PendingCreate(bundle.commit, bundle.welcome, devices.keys.toList()))
            }
        }

        // 4. Post, activate, announce the mode; then the group envelope and my profile.
        manager.finishCreate(created.conversationId, resumed = false)
        finishInit(created.conversationId)
        return GroupCreation(chatId, (withoutPackages + skipped).distinct())
    }

    /**
     * Queues the `group` envelope and this account's `profile` behind the mode envelope, and clears the marker in the
     * same transaction, so a kill leaves either the marker (done again) or both envelopes queued (never twice). A
     * failed mode post leaves the marker; [resumeInit] finishes it.
     */
    suspend fun finishInit(serverId: String) {
        if (repo.get(INIT_PREFIX + serverId)?.isNotEmpty() != true) return
        val chat = repo.chats().firstOrNull { it.conversationId == serverId }
        if (chat == null) {
            repo.set(INIT_PREFIX + serverId, ByteArray(0))
            return
        }
        manager.preferredConversation(chat.id) // sends the mode envelope first when it is still pending
        val title = chat.title
        val profile = ownProfile(java.util.UUID.randomUUID().toString().lowercase(), manager.clock.now())
        repo.transaction {
            if (title != null) outbox.enqueueControl(chat.id) { id, at -> ContentEnvelope.Group(id, at, title, chat.photo, chat.groupVer ?: 1) }
            if (profile != null) outbox.enqueueControl(chat.id) { _, _ -> profile }
            repo.set(INIT_PREFIX + serverId, ByteArray(0))
        }
    }

    /** Finishes every group whose envelopes were not queued yet (run at start-up after the pending creates, and by every flush). Failures stay marked. */
    suspend fun resumeInit() {
        for ((key, value) in repo.entries(INIT_PREFIX)) {
            if (value.isEmpty()) continue
            try {
                // Not while a create runs: its marker is set before its own finishInit, and a second finishInit then
                // would announce the mode and queue the group and profile envelopes twice, or ahead of the mode.
                manager.creating.withLock { finishInit(key.removePrefix(INIT_PREFIX)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Still marked: the next start-up tries again.
            }
        }
    }

    companion object {
        /** `kv` marker per group: the `group` and `profile` envelopes are not queued yet. Empty = done. */
        const val INIT_PREFIX = "group-init:"
        private const val MIN_OTHERS = 2
        private const val MAX_OTHERS = 99
    }
}
