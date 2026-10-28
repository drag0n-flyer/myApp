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
import org.example.app.contracts.MlsException
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
        val record = rig.ana1.manager.create("acc-bob")
        val group = GroupId(record.groupId)
        rig.server.rejectNextCommits = 1
        val epoch = rig.ana1.manager.postCommitWithRetry(record.serverId, group, emptyList(), listOf("ana2")) {
            rig.ana1.mls.remove(listOf("acc-ana/ana2"), group)
        }
        assertEquals(1, rig.ana1.syncs)
        assertEquals(2L, epoch)
        assertEquals(2L, rig.ana1.mls.epoch(group))
        assertEquals(3, rig.server.conversation(record.serverId)?.members?.size)

        val other = Rig().start()
        val otherRecord = other.ana1.manager.create("acc-bob")
        val otherGroup = GroupId(otherRecord.groupId)
        val postsBefore = other.server.commitPosts.size
        other.server.rejectNextCommits = 4
        assertFailsWith<SyncException.EpochConflict> {
            other.ana1.manager.postCommitWithRetry(otherRecord.serverId, otherGroup, emptyList(), listOf("ana2")) {
                other.ana1.mls.remove(listOf("acc-ana/ana2"), otherGroup)
            }
        }
        assertEquals(4, other.server.commitPosts.size - postsBefore, "one try and three retries")
        assertEquals(3, other.ana1.syncs, "a sync before each retry, none after the last rejection")
        assertEquals(1L, other.ana1.mls.epoch(otherGroup))
        // Nothing is left pending after a definite give-up: a new commit builds.
        other.ana1.lock.withLock { other.ana1.mls.remove(listOf("acc-ana/ana2"), otherGroup) }
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

    /**
     * The request never reached the server and nobody else committed: the commit stays pending (mls-profile
     * "Commits" step 5). Later attempts read the inbox and give up with CommitInFlight; they never discard it and never
     * post it again. Another device's commit at that epoch clears it, and the next attempt rebuilds.
     */
    @Test
    fun pendingCommitIsKeptUntilTheServerSettlesIt() = runTest {
        val rig = Rig().start()
        val ana = rig.ana1
        val record = ana.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val group = GroupId(record.groupId)
        suspend fun removeAna2() = ana.manager.postCommitWithRetry(record.serverId, group, emptyList(), listOf("ana2")) {
            ana.mls.remove(listOf("acc-ana/ana2"), group)
        }
        rig.server.holdNextCommitRequests = 1
        assertFailsWith<ServerException> { removeAna2() }
        val posts = rig.server.calls.count { it == "acc-ana/ana1 postCommit" }

        assertFailsWith<SyncException.CommitInFlight> { removeAna2() }
        assertFailsWith<SyncException.CommitInFlight> { removeAna2() }
        assertEquals(posts, rig.server.calls.count { it == "acc-ana/ana1 postCommit" }, "never posted again")
        assertFailsWith<MlsException.PendingCommit> { ana.lock.withLock { ana.mls.remove(listOf("acc-ana/ana2"), group) } }
        assertEquals(1L, ana.mls.epoch(group))

        // bob1 commits at that epoch and wins; ana's pending commit is cleared when she reads it.
        val bobGroup = GroupId(assertNotNull(rig.bob1.repo.conversation(record.serverId)).groupId)
        rig.bob1.manager.postCommitWithRetry(record.serverId, bobGroup, emptyList(), listOf("bob2")) {
            rig.bob1.mls.remove(listOf("acc-bob/bob2"), bobGroup)
        }
        assertEquals(3L, removeAna2())
        assertEquals(3L, ana.mls.epoch(group))
        assertEquals(listOf("acc-ana/ana1", "acc-bob/bob1"), rig.server.conversation(record.serverId)?.members?.toList())
        rig.assertLockDiscipline()
    }

    /**
     * The timed-out request lands late, just before the next commit would be posted. Discarding the pending commit
     * here would leave this device one epoch behind for good (its own echo can no longer be merged). Kept pending,
     * the echo merges it.
     */
    @Test
    fun lateCommitRequestDoesNotForkTheGroup() = runTest {
        val rig = Rig().start()
        val ana = rig.ana1
        val record = ana.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val group = GroupId(record.groupId)
        suspend fun removeAna2() = ana.manager.postCommitWithRetry(record.serverId, group, emptyList(), listOf("ana2")) {
            ana.mls.remove(listOf("acc-ana/ana2"), group)
        }
        rig.server.holdNextCommitRequests = 1
        assertFailsWith<ServerException> { removeAna2() }

        val previous = rig.server.onCall
        rig.server.onCall = { identity, call ->
            previous?.invoke(identity, call)
            if (identity == "acc-ana/ana1" && call == "postCommit") rig.server.releaseHeldCommits()
        }
        runCatching { removeAna2() }
        rig.server.onCall = previous
        rig.server.releaseHeldCommits()
        ana.processor.sync()

        assertEquals(rig.server.conversation(record.serverId)?.epoch, ana.mls.epoch(group), "in step with the server")
        assertEquals(2L, ana.mls.epoch(group))
        assertEquals(2L, ana.repo.conversation(record.serverId)?.epoch)
        assertTrue(ana.repo.allMessages().none { it.undecryptable })
        assertEquals(listOf("acc-ana/ana1", "acc-bob/bob1", "acc-bob/bob2"), rig.server.conversation(record.serverId)?.members?.toList())
        rig.assertLockDiscipline()
    }

    // Create with an unknown outcome (C1): the conversation is stored before the commit is posted.

    /** The reply is lost but the echo comes in the inbox read right after: the create still succeeds. */
    @Test
    fun lostCreateReplyIsSettledByTheEcho() = runTest {
        val rig = Rig().start()
        rig.server.loseNextCommitResponses = 1
        val record = rig.ana1.manager.create("acc-bob")
        assertEquals(listOf(record.serverId), rig.server.conversationIds())
        assertTrue(record.active)
        assertEquals(1L, record.epoch)
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        assertEquals(1, rig.server.commitPosts.size)
        assertEquals(1, rig.server.messages.size, "the mode envelope")
        assertEquals(4, rig.ana1.repo.members(record.serverId).size)
        rig.assertLockDiscipline()
    }

    /** The reply and the inbox read after it fail. The retry reuses the group; the peer's message lands in it. */
    @Test
    fun lostCreateReplyThenRetryKeepsOneGroup() = runTest {
        val rig = Rig().start()
        rig.server.loseNextCommitResponses = 1
        rig.server.failNextInboxReads = 1
        assertFailsWith<ServerException> { rig.ana1.manager.preferredConversation("acc-bob") }
        val id = rig.server.conversationIds().single()
        assertEquals(null, rig.ana1.repo.preferredConversation("acc-bob"), "pending, not usable yet")

        // The server applied it: bob joins and writes first.
        rig.bob1.processor.sync()
        rig.bob1.send(assertNotNull(rig.bob1.repo.conversation(id)), ContentEnvelope.Text("b-1", NOW, "hi"))

        val record = rig.ana1.manager.preferredConversation("acc-bob")
        assertEquals(id, record.serverId)
        assertEquals(listOf(id), rig.server.conversationIds(), "no second conversation")
        assertEquals(1, rig.ana1.repo.conversations("acc-bob").size)
        assertTrue(record.active)
        assertEquals(1L, record.epoch)
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        assertEquals(1, rig.server.commitPosts.size, "the echo settled it")
        assertEquals(id, rig.ana1.repo.message("b-1")?.conversationId)
        assertEquals(1, rig.server.messages.count { it.sender == "acc-ana/ana1" }, "the mode envelope")
        rig.assertLockDiscipline()
    }

    /** The request never reached the server: the retry posts the same commit again (at epoch 0 only this device can commit). */
    @Test
    fun createRequestThatNeverArrivedIsResent() = runTest {
        val rig = Rig().start()
        rig.server.holdNextCommitRequests = 1
        assertFailsWith<ServerException> { rig.ana1.manager.create("acc-bob") }
        val id = rig.server.conversationIds().single()
        assertEquals(0L, rig.server.conversation(id)?.epoch)

        val record = rig.ana1.manager.preferredConversation("acc-bob")
        assertEquals(id, record.serverId)
        assertEquals(listOf(id), rig.server.conversationIds())
        assertEquals(1L, record.epoch)
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        // The original lands late: refused, and nothing changes.
        assertTrue(rig.server.releaseHeldCommits().single().isFailure)
        rig.ana1.processor.sync()
        rig.bob1.processor.sync()
        assertEquals("e2ee", rig.bob1.repo.conversation(id)?.innerMode)
        assertEquals(1, rig.bob1.repo.conversations("acc-ana").size)
        rig.bob1.send(assertNotNull(rig.bob1.repo.conversation(id)), ContentEnvelope.Text("b-1", NOW, "hi"))
        rig.ana1.processor.sync()
        assertEquals(id, rig.ana1.repo.message("b-1")?.conversationId)
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        rig.assertLockDiscipline()
    }

    /** A pending create whose conversation the server no longer knows is abandoned, and a new one is made. */
    @Test
    fun pendingCreateTheServerRefusesIsAbandoned() = runTest {
        val rig = Rig().start()
        rig.server.holdNextCommitRequests = 1
        assertFailsWith<ServerException> { rig.ana1.manager.create("acc-bob") }
        val first = rig.server.conversationIds().single()
        rig.server.failConversationLookup = ServerException(404, "not_found", null, null)
        val record = rig.ana1.manager.preferredConversation("acc-bob")
        assertTrue(record.serverId != first)
        assertTrue(record.active)
        assertEquals(false, rig.ana1.repo.conversation(first)?.active)
        assertFalse(record.groupId.contentEquals(rig.ana1.repo.conversation(first)!!.groupId), "a fresh group")
        assertEquals(record.serverId, rig.ana1.repo.preferredConversation("acc-bob")?.serverId)
        rig.assertLockDiscipline()
    }

    /** The epoch check is refused with a 403: the commit may already be applied, so the create stays pending. */
    @Test
    fun forbiddenEpochCheckKeepsTheCreatePending() = runTest {
        val rig = Rig().start()
        rig.server.holdNextCommitRequests = 1
        assertFailsWith<ServerException> { rig.ana1.manager.create("acc-bob") }
        val first = assertNotNull(rig.ana1.repo.conversation(rig.server.conversationIds().single()))
        rig.server.failConversationLookup = ServerException(403, "forbidden", null, null)
        val refused = assertFailsWith<ServerException> { rig.ana1.manager.preferredConversation("acc-bob") }
        assertEquals(403, refused.status)
        assertTrue(PendingCreate.isPending(rig.ana1.repo, first.serverId), "kept, not abandoned")
        assertEquals(listOf(first.serverId), rig.server.conversationIds(), "no second conversation")

        rig.server.failConversationLookup = null
        val record = rig.ana1.manager.preferredConversation("acc-bob")
        assertEquals(first.serverId, record.serverId)
        assertTrue(record.groupId.contentEquals(first.groupId), "the same group")
        assertTrue(record.active)
        rig.assertLockDiscipline()
    }

    /** The epoch-0 post is refused for good (here 403): nothing was applied and no Welcome went out, so it is abandoned. */
    @Test
    fun createWhoseCommitPostIsRefusedIsAbandoned() = runTest {
        val rig = Rig().start()
        rig.server.failNextCommitPost = ServerException(403, "forbidden", null, null)
        assertEquals(403, assertFailsWith<ServerException> { rig.ana1.manager.create("acc-bob") }.status)
        val first = assertNotNull(rig.ana1.repo.conversation(rig.server.conversationIds().single()))
        assertFalse(first.active)
        assertFalse(PendingCreate.isPending(rig.ana1.repo, first.serverId), "abandoned")

        val record = rig.ana1.manager.create("acc-bob")
        assertTrue(record.serverId != first.serverId)
        assertFalse(record.groupId.contentEquals(first.groupId), "a fresh group")
        assertTrue(record.active)
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        rig.bob1.processor.sync()
        assertNotNull(rig.bob1.repo.conversation(record.serverId))
        rig.assertLockDiscipline()
    }

    /** A 408 or 429 on the epoch-0 post may have been applied: the create stays pending and the same commit is resent. */
    @Test
    fun createWhoseCommitPostTimesOutOrIsThrottledStaysPending() = runTest {
        for (status in listOf(408, 429)) {
            val rig = Rig().start()
            rig.server.failNextCommitPost = ServerException(status, "busy", null, null)
            assertFailsWith<ServerException> { rig.ana1.manager.create("acc-bob") }
            val first = assertNotNull(rig.ana1.repo.conversation(rig.server.conversationIds().single()))
            assertTrue(PendingCreate.isPending(rig.ana1.repo, first.serverId), "$status: kept")

            val record = rig.ana1.manager.create("acc-bob")
            assertEquals(first.serverId, record.serverId, "$status")
            assertTrue(record.groupId.contentEquals(first.groupId), "$status: the same group")
            assertEquals(listOf(first.serverId), rig.server.conversationIds())
            rig.assertLockDiscipline()
        }
    }

    /** Killed after the server applied the commit and before anything was written: recovery finishes the same conversation. */
    @Test
    fun crashAfterCreatePostRecoversOnRestart() = runTest {
        val rig = Rig().start()
        rig.server.afterCommitApplied = { rig.server.afterCommitApplied = null; throw SimulatedKill() }
        assertFailsWith<SimulatedKill> { rig.ana1.manager.create("acc-bob") }
        val id = rig.server.conversationIds().single()

        rig.ana1.restart()
        rig.server.failNextInboxReads = 1 // recovery cannot read the inbox: the server's epoch settles it
        rig.ana1.manager.resumePendingCreates()
        val record = assertNotNull(rig.ana1.repo.preferredConversation("acc-bob"))
        assertEquals(id, record.serverId)
        assertEquals(1L, record.epoch)
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        assertEquals(1, rig.server.commitPosts.size)
        assertEquals(1, rig.server.messages.size, "the mode envelope")

        // The echo comes later and is skipped; a new create reuses nothing and makes nothing.
        rig.ana1.processor.sync()
        assertEquals(1L, rig.ana1.mls.epoch(GroupId(record.groupId)))
        assertEquals(id, rig.ana1.manager.preferredConversation("acc-bob").serverId)
        assertEquals(listOf(id), rig.server.conversationIds())
        assertTrue(rig.ana1.repo.allMessages().none { it.undecryptable })
        rig.assertLockDiscipline()
    }

    /** Killed after the post; the peer writes; on restart the inbox runs first. The message lands in the pending conversation. */
    @Test
    fun messageForPendingConversationIsNotDiscarded() = runTest {
        val rig = Rig().start()
        rig.server.afterCommitApplied = { rig.server.afterCommitApplied = null; throw SimulatedKill() }
        assertFailsWith<SimulatedKill> { rig.ana1.manager.create("acc-bob") }
        val id = rig.server.conversationIds().single()
        rig.bob1.processor.sync()
        rig.bob1.send(assertNotNull(rig.bob1.repo.conversation(id)), ContentEnvelope.Text("b-1", NOW, "hi"))

        rig.ana1.restart()
        rig.ana1.processor.sync()
        val message = assertNotNull(rig.ana1.repo.message("b-1"))
        assertEquals(id, message.conversationId)
        assertEquals("acc-bob", message.chatId)
        val record = assertNotNull(rig.ana1.repo.preferredConversation("acc-bob"))
        assertEquals(1L, record.epoch)

        assertEquals(id, rig.ana1.manager.preferredConversation("acc-bob").serverId)
        assertEquals(listOf(id), rig.server.conversationIds())
        assertEquals(1, rig.server.messages.count { it.sender == "acc-ana/ana1" }, "the mode envelope, sent before anything else")
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
        // No sealer wired: the mode post fails and stays pending, never sent without escrow.
        val unsealed = ConversationManager(
            rig.ana1.me, rig.ana1.mls, rig.ana1.api, rig.ana1.repo, rig.ana1.lock, rig.ana1.clock, rig.ana1.membership,
            syncOnce = { rig.ana1.processor.sync() },
        )
        assertFailsWith<SyncException.EscrowKeyUnavailable> { unsealed.create("acc-bob") }
        assertTrue(rig.server.messages.isEmpty())

        val sealed = mutableListOf<String>()
        val manager = ConversationManager(
            rig.ana1.me, rig.ana1.mls, rig.ana1.api, rig.ana1.repo, rig.ana1.lock, rig.ana1.clock, rig.ana1.membership,
            syncOnce = { rig.ana1.processor.sync() },
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
