package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.GroupId
import org.example.app.contracts.ServerException
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.testing.FakeDeliveryServer

class ConversationManagerTest {
    @Test
    fun preferredIsSmallestId() = runTest {
        val rig = Rig().start()
        val a = async { rig.ana1.manager.create("acc-bob") }
        val b = async { rig.bob1.manager.create("acc-ana") }
        a.await(); b.await()
        assertEquals(2, rig.server.conversationIds().size)

        rig.ana1.processor.sync()
        rig.bob1.processor.sync()
        val first = rig.ana1.manager.preferredConversation("acc-bob")
        val second = rig.bob1.manager.preferredConversation("acc-ana")
        assertEquals(rig.server.conversationIds().min(), first.serverId)
        assertEquals(first.serverId, second.serverId)
        assertEquals(1, rig.ana1.repo.chats().size)
        assertEquals(1, rig.bob1.repo.chats().size)
        assertEquals(2, rig.ana1.repo.conversations("acc-bob").size)
        // An existing conversation is returned without creating another.
        assertEquals(2, rig.server.conversationIds().size)
        rig.assertLockDiscipline()
    }

    @Test
    fun createAddsAllDevicesAndStoresServerMode() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        val server = assertNotNull(rig.server.conversation(record.serverId))
        assertEquals(listOf("acc-ana/ana1", "acc-ana/ana2", "acc-bob/bob1", "acc-bob/bob2"), server.members.toList())
        assertEquals(1L, record.epoch)
        assertEquals("e2ee", record.mode)
        assertEquals("e2ee", record.innerMode)
        assertEquals(false, rig.ana1.repo.chat("acc-bob")?.isRequest)
        assertEquals(4, rig.ana1.repo.members(record.serverId).size)
        assertNotNull(rig.ana1.repo.safety("acc-bob"))
    }

    @Test
    fun createSendsModeFirst() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        // The commit (with the Welcomes) went first, then exactly one application message: the mode envelope.
        val calls = rig.server.calls.filter { it.startsWith("acc-ana/ana1 post") }
        assertEquals(listOf("acc-ana/ana1 postCommit", "acc-ana/ana1 postMessage"), calls)
        rig.server.messages.single { it.conversationId == record.serverId }
        // Bob joins, then reads it: the pin comes from the envelope.
        rig.bob1.processor.sync()
        assertEquals("e2ee", rig.bob1.repo.conversation(record.serverId)?.innerMode)
        assertTrue(rig.bob1.repo.allMessages().none { it.conversationId == record.serverId }, "the mode envelope is not a message row")
        rig.assertLockDiscipline()
    }

    @Test
    fun failedModePostIsSentBeforeAnythingElse() = runTest {
        val rig = Rig().start()
        rig.server.failNextMessages = 1
        assertFailsWith<ServerException> { rig.ana1.manager.create("acc-bob") }
        val id = rig.server.conversationIds().single()
        assertTrue(rig.server.messages.isEmpty())

        val record = rig.ana1.manager.preferredConversation("acc-bob")
        assertEquals(id, record.serverId)
        rig.ana1.send(record, ContentEnvelope.Text("m-1", 2, "hi"))
        assertEquals(2, rig.server.messages.size)
        rig.bob1.processor.sync()
        assertEquals("e2ee", rig.bob1.repo.conversation(id)?.innerMode)
        // A second ask does not announce again.
        rig.ana1.manager.preferredConversation("acc-bob")
        assertEquals(2, rig.server.messages.size)
    }

    @Test
    fun staleEpochRetriesAtMostThree() = runTest {
        val rig = Rig().start()
        rig.server.rejectNextCommits = 1
        val record = rig.ana1.manager.create("acc-bob")
        assertEquals(1, rig.ana1.syncs)
        assertEquals(1L, record.epoch)
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        assertEquals(4, rig.server.conversation(record.serverId)?.members?.size)

        val other = Rig().start()
        other.server.rejectNextCommits = 4
        assertFailsWith<SyncException.EpochConflict> { other.ana1.manager.create("acc-bob") }
        assertEquals(4, other.server.commitPosts.size, "one try and three retries")
        assertEquals(3, other.ana1.syncs, "a sync before each retry, none after the last rejection")
        assertTrue(other.ana1.repo.conversations("acc-bob").isEmpty())
        assertEquals(1, other.server.conversation(other.server.conversationIds().single())?.members?.size)
        rig.assertLockDiscipline(); other.assertLockDiscipline()
    }

    /** bob1 commits first, so ana1's commit is rejected for real; the sync moves her group on and the rebuilt commit lands. */
    @Test
    fun staleEpochRebuildsAfterSync() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val bobGroup = GroupId(assertNotNull(rig.bob1.repo.conversation(record.serverId)).groupId)
        rig.bob1.manager.postCommitWithRetry(record.serverId, bobGroup, emptyList(), listOf("bob2")) {
            rig.bob1.mls.remove(listOf("acc-bob/bob2"), bobGroup)
        }

        val group = GroupId(record.groupId)
        var builds = 0
        val newEpoch = rig.ana1.manager.postCommitWithRetry(record.serverId, group, emptyList(), listOf("ana2")) {
            builds += 1
            rig.ana1.mls.remove(listOf("acc-ana/ana2"), group)
        }
        assertEquals(2, builds)
        assertEquals(3L, newEpoch)
        assertEquals(1, rig.ana1.syncs)
        assertEquals(3L, rig.ana1.mls.epoch(group))
        assertEquals(3L, rig.ana1.repo.conversation(record.serverId)?.epoch)
        assertEquals(listOf("acc-ana/ana1", "acc-bob/bob1"), rig.server.conversation(record.serverId)?.members?.toList())
        rig.assertLockDiscipline()
    }

    /** The server applied the commit but the reply was lost: the pending commit stays, the inbox is read, the echo merges it. */
    @Test
    fun unknownOutcomeFetchesInboxFirst() = runTest {
        val rig = Rig().start()
        val ana = rig.ana1
        val theirs = ana.api.claimKeyPackages("acc-bob")
        val created = ana.api.createConversation("acc-bob")
        val group = ana.lock.withLock { ana.mls.createGroup() }
        rig.server.loseNextCommitResponses = 1
        assertFailsWith<ServerException> {
            ana.manager.postCommitWithRetry(created.conversationId, group, theirs.keys.toList(), emptyList()) {
                ana.mls.add(theirs.values.toList(), group)
            }
        }
        assertEquals(1, rig.server.commitPosts.size, "the same commit is never posted again")
        val afterPost = rig.server.calls.dropWhile { it != "acc-ana/ana1 postCommit" }
        assertTrue("acc-ana/ana1 inbox" in afterPost, "the inbox is fetched after an unknown outcome")
        assertEquals(1L, ana.mls.epoch(group), "the echo merged the pending commit")
        assertTrue(rig.server.pending("acc-ana/ana1").isEmpty())

        // The next commit on the group builds on the merged state.
        val epoch = ana.manager.postCommitWithRetry(created.conversationId, group, emptyList(), listOf("bob2")) {
            ana.mls.remove(listOf("acc-bob/bob2"), group)
        }
        assertEquals(2L, epoch)
        assertEquals(listOf(0L, 1L), rig.server.commitPosts.map { it.third })
        rig.assertLockDiscipline()
    }

    /** The post failed before the server applied it: the next commit reads the inbox, sees no echo, checks the server's epoch and discards. */
    @Test
    fun pendingCommitNeverAppliedIsDiscardedAfterInboxAndEpochCheck() = runTest {
        val rig = Rig().start()
        val ana = rig.ana1
        val theirs = ana.api.claimKeyPackages("acc-bob")
        val created = ana.api.createConversation("acc-bob")
        val group = ana.lock.withLock { ana.mls.createGroup() }
        val previous = rig.server.onCall
        var dropOnce = true
        rig.server.onCall = { identity, call ->
            previous?.invoke(identity, call)
            if (call == "postCommit" && dropOnce) { dropOnce = false; throw ServerException(0, "network", null, null) }
        }
        assertFailsWith<ServerException> {
            ana.manager.postCommitWithRetry(created.conversationId, group, theirs.keys.toList(), emptyList()) {
                ana.mls.add(theirs.values.toList(), group)
            }
        }
        assertEquals(0L, ana.mls.epoch(group))
        val epoch = ana.manager.postCommitWithRetry(created.conversationId, group, theirs.keys.toList(), emptyList()) {
            ana.mls.add(theirs.values.toList(), group)
        }
        assertEquals(1L, epoch)
        assertEquals(1L, ana.mls.epoch(group))
        rig.assertLockDiscipline()
    }

    @Test
    fun definiteRejectionDiscardsPendingCommit() = runTest {
        val rig = Rig().start()
        val ana = rig.ana1
        val theirs = ana.api.claimKeyPackages("acc-bob")
        val group = ana.lock.withLock { ana.mls.createGroup() }
        assertFailsWith<ServerException> {
            ana.manager.postCommitWithRetry("conv-missing", group, theirs.keys.toList(), emptyList()) {
                ana.mls.add(theirs.values.toList(), group)
            }
        }
        // No pending commit is left: a new one builds.
        ana.lock.withLock { ana.mls.add(theirs.values.toList(), group) }
    }

    @Test
    fun joinedFromStrangerIsRequest() = runTest {
        val server = FakeDeliveryServer()
        val carol = TestDevice(server, "acc-carol", "carol1")
        val bob = TestDevice(server, "acc-bob", "bob1")
        val dave = TestDevice(server, "acc-dave", "dave1")
        for (d in listOf(carol, bob, dave)) d.api.uploadKeyPackages(d.mls.makeKeyPackages(2))
        carol.manager.create("acc-bob")
        bob.processor.sync()
        assertEquals(true, bob.repo.chat("acc-carol")?.isRequest)
        val conversation = bob.repo.conversations("acc-carol").single()
        assertEquals("e2ee", conversation.mode)
        assertFalse(conversation.modeMismatch)

        bob.repo.upsertContact(ContactRecord("acc-dave", "dave", "Dave", true, NOW))
        dave.manager.create("acc-bob")
        bob.processor.sync()
        assertEquals(false, bob.repo.chat("acc-dave")?.isRequest)
    }

    @Test
    fun announceModeNeedsInnerMode() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        assertFailsWith<SyncException.ModeUnknown> { rig.ana1.manager.announceMode(record.copy(innerMode = null)) }
    }

    @Test
    fun serverReadableModeNeedsEscrowSealer() = runTest {
        val rig = Rig("server_readable").start()
        // No sealer wired (D5 adds it): the mode post fails and stays pending, never sent without escrow.
        assertFailsWith<SyncException.EscrowKeyUnavailable> { rig.ana1.manager.create("acc-bob") }
        assertTrue(rig.server.messages.isEmpty())

        val sealed = mutableListOf<String>()
        val manager = ConversationManager(
            rig.ana1.me, rig.ana1.mls, rig.ana1.api, rig.ana1.repo, rig.ana1.lock, rig.ana1.clock, rig.ana1.membership,
            escrow = EscrowSealer { plaintext, _, conversationId ->
                sealed += conversationId
                assertIs<ContentEnvelope.Mode>(ContentCodec.decode(plaintext))
                "ESCROW".toByteArray()
            },
        )
        val record = manager.preferredConversation("acc-bob")
        assertEquals(listOf(record.serverId), sealed)
        assertEquals("server_readable", record.mode)
        assertEquals("server_readable", record.innerMode)
        assertEquals("ESCROW", rig.server.messages.single().escrow?.decodeToString())
    }
}
