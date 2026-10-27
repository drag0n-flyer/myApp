package org.example.app.messaging.sync

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.GroupId
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.SnapshotSource
import org.example.app.messaging.content.LinkSnapshot
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.Clock
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.FolderRecord
import org.example.app.messaging.ports.MlsLock
import org.example.app.messaging.ports.Repository

sealed class DeviceSyncException(message: String) : IllegalArgumentException(message) {
    /** `import` got bytes that are not a [LinkSnapshot] of version 1. */
    object InvalidSnapshot : DeviceSyncException("invalidSnapshot")
}

/**
 * Own-device changes (Design 5, 18, 19; mls-profile "Removing a device"): adds new own devices to every conversation,
 * removes revoked ones, wipes this device, and moves the link snapshot. Port of the iPhone `DeviceSync`.
 *
 * Commits go through [ConversationManager.postCommitWithRetry], so the epoch-conflict retry, the pending-commit rule
 * (never discarded, never posted twice) and the lock discipline are the conversation manager's. Engine reads here
 * run under the [MlsLock]; no network call does. Never call these from inside `InboxProcessor.drain`.
 *
 * @param wipeMls deletes the MLS engine's storage. Required in the app: a revoked device must not keep key material.
 * @param deleteSigningKey deletes this account's MLS signing key; runs on every wipe, after the other steps.
 * @param mediaDirectory deleted on a wipe when set.
 */
class DeviceSync(
    private val me: LocalDevice,
    private val mls: MlsEngine,
    private val server: DeliveryApi,
    private val repo: Repository,
    private val lock: MlsLock,
    private val clock: Clock,
    private val conversations: ConversationManager,
    private val keyPackages: KeyPackagePool,
    private val wipeMls: suspend () -> Unit = {},
    private val deleteSigningKey: suspend () -> Unit = {},
    private val mediaDirectory: File? = null,
) : SnapshotSource {
    /** The group already has (or already lacks) the member by the time the commit is rebuilt after a stale epoch. */
    private class AlreadyDone : Exception()

    private fun activeConversations(): List<ConversationRecord> =
        repo.chats().flatMap { repo.conversations(it.id) }.filter { it.active }

    private suspend fun membersOf(group: GroupId): Set<String> = lock.withLock { mls.members(group).toSet() }

    // Own devices

    /**
     * Brings every active conversation in line with `GET /v1/devices`:
     * - an own device that is listed and not in the group is added, then the `mode` envelope is sent again so the new
     *   device learns it (Design 18);
     * - an own device that is in the group and no longer listed is removed (a device that missed `device.revoked`
     *   catches up here; the profile allows 24 hours from coming online).
     * Each conversation is tried even when another fails; the first error is thrown at the end.
     */
    suspend fun reconcileOwnDevices() {
        val listed = server.devices().map { it.id }.toSet()
        val others = listed - me.deviceId
        var failure: Exception? = null
        for (conversation in activeConversations()) {
            try {
                if (others.isNotEmpty()) addMissing(others, conversation)
                removeUnlisted(listed, conversation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }

    private suspend fun addMissing(own: Set<String>, conversation: ConversationRecord) {
        val group = GroupId(conversation.groupId)
        suspend fun missing(members: Set<String>) = own.filter { "${me.accountId}/$it" !in members }.sorted()
        val todo = missing(membersOf(group))
        if (todo.isEmpty()) return

        // Only the missing devices (never this one, never one already in the group).
        val claimed = server.claimKeyPackages(me.accountId, todo).filterKeys { it in todo }
        if (claimed.isEmpty()) throw SyncException.NoKeyPackages

        try {
            conversations.postCommitWithRetry(conversation.serverId, group, claimed.keys.toList(), emptyList()) {
                // Under the lock. After a stale epoch the inbox has been read: another own device may have added them.
                val use = claimed.filterKeys { it in missing(mls.members(group).toSet()) }
                if (use.isEmpty()) throw AlreadyDone()
                mls.add(use.values.toList(), group)
            }
        } catch (_: AlreadyDone) {
            return
        }
        // postCommitWithRetry merged the commit and stored the epoch in one locked step.

        // The committer sends the mode again (Design 18). The marker makes a failed send retry on the next send.
        val fresh = repo.conversation(conversation.serverId)?.takeIf { it.innerMode != null } ?: return
        ModePin.setPending(repo, fresh.serverId, true)
        conversations.announceMode(fresh)
    }

    private suspend fun removeUnlisted(listed: Set<String>, conversation: ConversationRecord) {
        val prefix = "${me.accountId}/"
        val stale = membersOf(GroupId(conversation.groupId))
            .filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.filter { it != me.deviceId && it !in listed }.sorted()
        for (deviceId in stale) remove(deviceId, conversation)
    }

    /**
     * Removes `"<me>/<deviceId>"` from every group that has it. If the revoked device is this one, the device wipes
     * itself instead (it can no longer post commits). Every conversation is tried; the first error is thrown at the end.
     */
    suspend fun handleRevoked(deviceId: String) {
        if (deviceId == me.deviceId) {
            wipeLocalData()
            return
        }
        var failure: Exception? = null
        for (conversation in activeConversations()) {
            try {
                remove(deviceId, conversation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }

    private suspend fun remove(deviceId: String, conversation: ConversationRecord) {
        val identity = "${me.accountId}/$deviceId"
        val group = GroupId(conversation.groupId)
        if (identity !in membersOf(group)) return
        try {
            conversations.postCommitWithRetry(conversation.serverId, group, emptyList(), listOf(deviceId)) {
                if (identity !in mls.members(group)) throw AlreadyDone()
                mls.remove(listOf(identity), group)
            }
        } catch (_: AlreadyDone) {
            return
        }
    }

    // KeyPackages

    suspend fun replenishKeyPackages() = keyPackages.refillIfDue()

    // Wipe

    /**
     * Deletes the database rows, the media directory, the MLS storage and the signing key. Every step runs; the first
     * error is thrown at the end. One locked step with no network inside, so nothing processes an item into a
     * half-deleted store.
     */
    suspend fun wipeLocalData() {
        withContext(NonCancellable) {
            lock.withLock {
                var failure: Exception? = null
                fun step(body: () -> Unit) = try { body() } catch (e: Exception) { failure = failure ?: e }
                step { repo.wipe() }
                step { mediaDirectory?.let { if (it.exists() && !it.deleteRecursively()) error("media not deleted") } }
                try { wipeMls() } catch (e: Exception) { failure = failure ?: e }
                try { deleteSigningKey() } catch (e: Exception) { failure = failure ?: e }
                failure?.let { throw it }
            }
        }
    }

    // Link snapshot

    override suspend fun export(): ByteArray {
        val contacts = repo.contacts().map { LinkSnapshot.Contact(it.accountId, it.username, it.displayName, it.isContact, it.addedAt) }
        val chats = repo.chats().map { LinkSnapshot.Chat(it.id, it.isRequest, it.muted) }
        val folders = repo.folders().map { LinkSnapshot.Folder(it.id, it.name, it.position, repo.folderChats(it.id)) }
        return LinkSnapshot(
            v = 1, contacts = contacts, chats = chats, folders = folders, blocked = repo.blockedIds().sorted(),
            settings = repo.entries(SETTINGS_PREFIX).toSortedMap(),
        ).encode()
    }

    /**
     * Applies a snapshot in one transaction. Existing rows are kept as they are (chats and folders by ID).
     * @throws DeviceSyncException.InvalidSnapshot for bytes that are not a version 1 snapshot; nothing changes.
     */
    override suspend fun import(bytes: ByteArray) {
        val snapshot = try {
            LinkSnapshot.decode(bytes)
        } catch (_: SerializationException) {
            throw DeviceSyncException.InvalidSnapshot
        } catch (_: IllegalArgumentException) {
            throw DeviceSyncException.InvalidSnapshot
        }
        if (snapshot.v != 1) throw DeviceSyncException.InvalidSnapshot
        val now = clock.now()
        repo.transaction {
            for (c in snapshot.contacts) repo.upsertContact(ContactRecord(c.accountID, c.username, c.displayName, c.isContact, c.addedAt))
            for (c in snapshot.chats) if (repo.chat(c.id) == null) repo.upsertChat(ChatRecord(c.id, c.isRequest, 0, c.muted, now))
            val existing = repo.folders().map { it.id }.toSet()
            for (f in snapshot.folders) {
                if (f.id in existing) continue
                repo.addFolder(FolderRecord(f.id, f.name, f.position))
                repo.setFolderChats(f.id, f.chatIDs)
            }
            for (id in snapshot.blocked) if (!repo.isBlocked(id)) repo.block(id, now)
            for ((key, value) in snapshot.settings) if (key.startsWith(SETTINGS_PREFIX)) repo.set(key, value)
        }
    }

    companion object {
        /** Settings that travel in the snapshot live in `kv` under this prefix. */
        const val SETTINGS_PREFIX = "setting."
    }
}
