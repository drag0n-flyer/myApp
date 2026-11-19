package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.GroupId
import org.example.app.contracts.ServerException
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SystemEvent
import org.example.app.messaging.ChatMapping
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.MyState

/** Lead rulings from the G2 review: lookups, own key packages, rejoining, "You were added", queued envelopes. */
class GroupRulingsTest {
    @Test
    fun aWelcomeWhoseLookupIsRefusedIsParkedAndMakesNoPlaceholderChat() = runTest {
        for (status in listOf(403, 404)) {
            val rig = Rig().start()
            rig.ana1.manager.create("acc-bob")
            rig.server.failConversationLookup = ServerException(status, "x", null, null)
            rig.bob1.processor.sync()
            assertTrue(rig.bob1.repo.rawItems(10).isEmpty(), "not retried for ever ($status)")
            assertTrue(rig.bob1.repo.chats().isEmpty(), "no chat ($status)")
            assertTrue(rig.bob1.repo.allMessages().isEmpty(), "no placeholder ($status)")
            assertTrue(rig.bob1.repo.entries("parked:").isNotEmpty(), "kept ($status)")
        }
    }

    @Test
    fun aWelcomeWhoseLookupFailsOnTheNetworkIsRetried() = runTest {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.server.failConversationLookup = ServerException(503, "unavailable", null, null)
        rig.bob1.processor.sync()
        assertTrue(rig.bob1.repo.rawItems(10).isNotEmpty())
        rig.server.failConversationLookup = null
        rig.bob1.processor.sync()
        assertNotNull(rig.bob1.repo.conversation(record.serverId))
    }

    @Test
    fun theCreatorsOwnKeyPackageIsNotUsedUp() = runTest {
        val w = GroupWorld(mapOf("acc-ana" to listOf("ana1", "ana2"), "acc-bob" to listOf("bob1"), "acc-cat" to listOf("cat1"))).start()
        val ana = w.device("ana1")
        ana.groups.create(listOf("acc-bob", "acc-cat"), "Trio", null)
        assertEquals(3, ana.api.keyPackageCount(), "this device's package was not claimed")
        assertEquals(2, w.device("ana2").api.keyPackageCount(), "the other device's was")
        assertTrue(w.server.conversation("conv-0001")!!.members.contains("acc-ana/ana2"))
    }

    @Test
    fun aWelcomeForAGroupIAmOutOfMakesMeAMemberAgain() = runTest {
        val w = GroupWorld.trio()
        w.device("ana1").groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        w.device("ana1").outbox.flush()
        val bob = w.device("bob1")
        bob.processor.sync()
        val chatId = "g:conv-0001"
        val old = bob.repo.conversations(chatId).single()
        bob.repo.setMyState(chatId, MyState.REMOVED)
        bob.repo.upsertConversation(old.copy(active = false))
        val info = w.server.api("acc-bob", "bob1").conversation("conv-0001")
        val decision = GroupJoinCheck.decide(info, "acc-bob", "acc-ana", bob.repo)
        val join = (decision as GroupJoinCheck.Decision.Join).join
        val fresh = bob.lock.withLock { bob.mls.createGroup() }
        val plan = bob.manager.planJoin(fresh, "conv-0001", "e2ee", join)
        bob.repo.transaction { plan.store(bob.repo) }
        assertEquals(MyState.MEMBER, bob.repo.chat(chatId)!!.myState)
        assertTrue(bob.repo.conversation("conv-0001")!!.active)
        assertEquals(fresh.raw.toList(), bob.repo.conversation("conv-0001")!!.groupId.toList())
        assertEquals("Book club", bob.repo.chat(chatId)!!.title, "name and choices are kept")
    }

    @Test
    fun aGroupIAmStillInIsNotJoinedTwice() = runTest {
        val w = GroupWorld.trio()
        w.device("ana1").groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        val bob = w.device("bob1")
        bob.processor.sync()
        val info = w.server.api("acc-bob", "bob1").conversation("conv-0001")
        assertTrue(GroupJoinCheck.decide(info, "acc-bob", "acc-ana", bob.repo) is GroupJoinCheck.Decision.Drop)
    }

    @Test
    fun theAddedYouLineNamesTheSenderOnlyWhenTheServerListsThem() = runTest {
        val w = GroupWorld.trio()
        w.device("ana1").groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        val bob = w.device("bob1")
        val info = w.server.api("acc-bob", "bob1").conversation("conv-0001")
        val named = (GroupJoinCheck.decide(info, "acc-bob", "acc-ana", bob.repo) as GroupJoinCheck.Decision.Join).join
        assertEquals("acc-ana", named.addedBy)
        val stranger = (GroupJoinCheck.decide(info, "acc-bob", "acc-eve", bob.repo) as GroupJoinCheck.Decision.Join).join
        assertNull(stranger.addedBy)
    }

    @Test
    fun anUnnamedAddedYouLineIsStoredAndShownAsYouWereAdded() = runTest {
        val w = GroupWorld.trio()
        w.device("ana1").groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        val bob = w.device("bob1")
        val info = w.server.api("acc-bob", "bob1").conversation("conv-0001")
        val join = (GroupJoinCheck.decide(info, "acc-bob", "acc-eve", bob.repo) as GroupJoinCheck.Decision.Join).join
        val fresh = bob.lock.withLock { bob.mls.createGroup() }
        val plan = bob.manager.planJoin(fresh, "conv-0001", "e2ee", join)
        bob.repo.transaction { plan.store(bob.repo) }
        val line = bob.repo.messages("g:conv-0001", null, 10).single { it.id.startsWith("sys-added-") }
        assertEquals("""{"addedYou":{}}""", line.envelope.decodeToString())
        val shown = ChatMapping.message(line, null, emptyList(), true) { it }
        assertEquals(MessageContent.System(SystemEvent.AddedYou(null)), shown.content)
    }

    @Test
    fun theGroupAndProfileEnvelopesAreQueuedAfterTheModeAndTheMarkerIsClearedWithThem() = runTest {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        val creator = GroupCreator(ana.manager, ana.repo, ana.outbox, ownProfile = { id, at -> ContentEnvelope.Profile(id, at, "Ana", null, 1) })
        val made = creator.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        assertTrue(ModePinFor(ana, "conv-0001").not(), "the mode went out first")
        assertEquals(setOf("group", "profile"), queuedKinds(ana))
        assertTrue(ana.repo.entries(GroupCreator.INIT_PREFIX).all { it.value.isEmpty() }, "marker cleared")
        assertEquals("g:conv-0001", made.chatId)
    }

    @Test
    fun aFailedModePostLeavesTheMarkerAndNothingQueuedUntilItIsResumed() = runTest {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        val creator = GroupCreator(ana.manager, ana.repo, ana.outbox, ownProfile = { id, at -> ContentEnvelope.Profile(id, at, "Ana", null, 1) })
        w.server.failNextMessages = 1
        runCatching { creator.create(listOf("acc-bob", "acc-cat"), "Book club", null) }
        assertTrue(queuedKinds(ana).isEmpty())
        assertTrue(ana.repo.entries(GroupCreator.INIT_PREFIX).any { it.value.isNotEmpty() })
        creator.resumeInit()
        assertEquals(setOf("group", "profile"), queuedKinds(ana))
        assertTrue(ana.repo.entries(GroupCreator.INIT_PREFIX).all { it.value.isEmpty() })
        creator.resumeInit()
        assertEquals(2, ana.repo.dueOutbox(Long.MAX_VALUE).size, "queued once")
    }

    @Test
    fun resumingTheMarkerWaitsForAGroupCreateInProgress() = runTest {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        val creator = GroupCreator(ana.manager, ana.repo, ana.outbox, ownProfile = { id, at -> ContentEnvelope.Profile(id, at, "Ana", null, 1) })
        w.server.failNextMessages = 1
        runCatching { creator.create(listOf("acc-bob", "acc-cat"), "Book club", null) }
        assertTrue(queuedKinds(ana).isEmpty())
        // A create holds the lock from its marker to the end of its own finishInit; a flush's resume must not run inside that.
        ana.manager.creating.lock()
        val resume = launch { creator.resumeInit() }
        testScheduler.runCurrent()
        assertTrue(queuedKinds(ana).isEmpty(), "the resume waits for the create")
        ana.manager.creating.unlock()
        resume.join()
        assertEquals(setOf("group", "profile"), queuedKinds(ana))
    }

    private fun ModePinFor(d: TestDevice, serverId: String) = ModePin.isPending(d.repo, serverId)

    private fun queuedKinds(d: TestDevice): Set<String> =
        d.repo.dueOutbox(Long.MAX_VALUE).map { ContentCodec.decode(it.envelope) }.map {
            when (it) { is ContentEnvelope.Group -> "group"; is ContentEnvelope.Profile -> "profile"; else -> "other" }
        }.toSet()
}
