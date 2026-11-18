package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.GroupId
import org.example.app.contracts.GroupMemberInfo
import org.example.app.messaging.ports.ChatKind
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.ports.MyState

/** A [GroupSync] per device of a [GroupWorld], wired to that device's inbox like the app wires it. */
class GroupSyncFixture(val w: GroupWorld) {
    private val syncs = HashMap<String, GroupSync>()

    fun of(d: TestDevice): GroupSync = syncs.getOrPut(d.me.identity) {
        GroupSync(d.me, d.mls, d.api, d.repo, d.lock, d.clock, d.manager, d.outbox, syncOnce = { d.processor.sync() }, log = { d.logs += it })
            .also { s -> d.processor.onMembers = { s.note(it) } }
    }

    /** The inbox, then the group sync it triggered. */
    suspend fun catchUp(d: TestDevice) {
        of(d)
        d.processor.sync()
        of(d).runPending()
        d.processor.sync()
    }

    /** ana makes a group with bob, cat and dan; every device joins. */
    suspend fun createdGroup(): String {
        val ana = w.device("ana1")
        val chat = ana.groups.create(listOf("acc-bob", "acc-cat", "acc-dan"), "Book club", null).chatId
        ana.outbox.flush()
        for (d in w.all) catchUp(d)
        return chat
    }
}

internal fun TestDevice.lines(chatId: String): List<String> =
    repo.messages(chatId, null, 200).filter { it.senderAccount == "system" && it.id.startsWith("sys-members-") }.sortedBy { it.id }.map { it.envelope.decodeToString() }

internal fun TestDevice.groupIdentities(chatId: String): List<String> =
    kotlinx.coroutines.runBlocking { mls.members(GroupId(repo.preferredConversation(chatId)!!.groupId)) }

class GroupMembersSyncTest {
    @Test
    fun missedItemsThatJumpTheVersionByThreeCostOneFetchAndGiveTheRightLines() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        val chat = f.createdGroup()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        ana.api.removeAccount("conv-0001", "acc-dan")
        ana.api.setRole("conv-0001", "acc-bob", "admin")
        ana.api.removeAccount("conv-0001", "acc-cat")
        f.of(bob)
        bob.processor.sync()
        val before = w.server.calls.count { it == "acc-bob/bob1 conversation" }
        f.of(bob).runPending()
        assertEquals(1, w.server.calls.count { it == "acc-bob/bob1 conversation" } - before, "three items, one fetch")
        assertEquals(listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "admin")), bob.repo.groupMembers(chat))
        assertEquals(
            listOf(
                """{"madeAdmin":{}}""",
                """{"groupRemoved":{"who":"acc-cat"}}""",
                """{"groupRemoved":{"who":"acc-dan"}}""",
            ),
            bob.lines(chat),
        )
        // Read again: nothing new, no second set of lines.
        f.of(bob).noteAll()
        f.of(bob).runPending()
        assertEquals(3, bob.lines(chat).size)
    }

    @Test
    fun theRemovedAccountsDevicesAreTakenOutOfTheMlsGroupInOneCommit() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        val chat = f.createdGroup()
        val bob = w.device("bob1")
        w.device("ana1").api.removeAccount("conv-0001", "acc-dan")
        val commitsBefore = w.server.commitPosts.count { it.first == "acc-bob/bob1" }
        f.catchUp(bob)
        assertEquals(1, w.server.commitPosts.count { it.first == "acc-bob/bob1" } - commitsBefore)
        assertTrue(bob.groupIdentities(chat).none { it.startsWith("acc-dan/") }, "${bob.groupIdentities(chat)}")
        assertEquals(1, bob.lines(chat).size)
    }

    @Test
    fun twoMembersRacingToRemoveTheSameDevicesOneCommitLandsAndNoLineIsDoubled() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        val chat = f.createdGroup()
        val bob1 = w.device("bob1")
        val cat1 = w.device("cat1")
        w.device("ana1").api.removeAccount("conv-0001", "acc-dan")
        // Both read their inbox (the members item) before either commits.
        f.of(bob1); f.of(cat1)
        bob1.processor.sync(); cat1.processor.sync()
        val epochBefore = w.server.conversation("conv-0001")!!.epoch
        f.of(bob1).runPending()
        f.of(cat1).runPending()
        assertEquals(epochBefore + 1, w.server.conversation("conv-0001")!!.epoch, "exactly one removal commit landed")
        assertTrue(w.server.conversation("conv-0001")!!.members.none { it.startsWith("acc-dan/") })
        for (d in listOf(bob1, cat1)) {
            f.catchUp(d)
            assertEquals(1, d.lines(chat).size, d.me.identity)
            assertTrue(d.groupIdentities(chat).none { it.startsWith("acc-dan/") })
        }
    }

    @Test
    fun aLeaveIsNamedAsALeaveWhenTheServerNamesTheLeaverAsTheOnlyChange() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        val chat = f.createdGroup()
        w.device("cat1").api.removeAccount("conv-0001", "acc-cat")
        f.catchUp(w.device("bob1"))
        assertEquals(listOf("""{"groupLeft":{"who":"acc-cat"}}"""), w.device("bob1").lines(chat))
    }

    @Test
    fun anAdminsRemovalIsNamedWithTheAdminAndMyOwnIsByYou() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        val chat = f.createdGroup()
        w.device("ana1").api.removeAccount("conv-0001", "acc-dan")
        f.catchUp(w.device("bob1"))
        f.catchUp(w.device("ana1"))
        assertEquals(listOf("""{"groupRemoved":{"by":"acc-ana","who":"acc-dan"}}"""), w.device("bob1").lines(chat))
        assertEquals(listOf("""{"groupRemoved":{"byYou":true,"who":"acc-dan"}}"""), w.device("ana1").lines(chat))
    }

    @Test
    fun aServerAddedAccountWithDevicesIsAddedByTheClientThatSeesItAndJoinsTheGroup() = runTest {
        val w = GroupWorld(
            mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("bob1"), "acc-cat" to listOf("cat1"), "acc-eve" to listOf("eve1")),
        ).start()
        val f = GroupSyncFixture(w)
        val ana = w.device("ana1")
        val chat = ana.groups.create(listOf("acc-bob", "acc-cat"), "Trio", null).chatId
        ana.outbox.flush()
        for (d in w.all.filter { it.me.accountId != "acc-eve" }) f.catchUp(d)
        ana.api.addAccounts("conv-0001", listOf("acc-eve"))
        f.catchUp(ana) // the owner sees eve listed and missing, adds her device and tells it the name
        assertTrue(w.server.conversation("conv-0001")!!.members.contains("acc-eve/eve1"))
        ana.outbox.flush()
        val eve = w.device("eve1")
        f.catchUp(eve)
        assertEquals(ChatKind.GROUP, eve.repo.chat(chat)!!.kind)
        assertEquals("Trio", eve.repo.chat(chat)!!.title, "the new device is told the name")
        assertEquals(listOf("acc-ana", "acc-bob", "acc-cat", "acc-eve"), eve.repo.groupMembers(chat).map { it.accountId })
        for (d in listOf("bob1", "cat1")) f.catchUp(w.device(d))
        assertEquals(listOf("""{"groupAdded":{"by":"acc-ana","who":"acc-eve"}}"""), w.device("cat1").also { f.catchUp(it) }.lines(chat))
    }

    @Test
    fun anAccountWithNoDeviceReadyIsListedAndAddedLaterByTheNextClientThatSeesItMissing() = runTest {
        val w = GroupWorld(
            mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("bob1"), "acc-cat" to listOf("cat1"), "acc-eve" to listOf("none-eve1")),
        ).start()
        val f = GroupSyncFixture(w)
        val ana = w.device("ana1")
        val chat = ana.groups.create(listOf("acc-bob", "acc-cat"), "Trio", null).chatId
        ana.outbox.flush()
        for (d in w.all.filter { it.me.accountId != "acc-eve" }) f.catchUp(d)
        ana.api.addAccounts("conv-0001", listOf("acc-eve"))
        val result = f.of(ana).reconcile("conv-0001")
        assertEquals(listOf("acc-eve"), result.notAddedYet)
        assertTrue("acc-eve" in ana.repo.groupMembers(chat).map { it.accountId }, "shown as a member")
        // Its device comes online with KeyPackages; the next client that looks adds it.
        val eve = w.device("none-eve1")
        eve.api.uploadKeyPackages(eve.mls.makeKeyPackages(2))
        val later = f.of(w.device("bob1")).reconcile("conv-0001")
        assertEquals(emptyList(), later.notAddedYet)
        assertTrue(w.server.conversation("conv-0001")!!.members.contains("acc-eve/none-eve1"))
    }

    @Test
    fun aMembersItemForAnUnknownConversationIsIgnoredAndAServerGroupWithoutALocalChatIsNeverFetched() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        w.device("ana1").groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        val bob = w.device("bob1") // the server lists bob in conv-0001, but he never read his Welcome: no local chat
        val sync = f.of(bob)
        assertFalse(sync.note(org.example.app.contracts.MembersEvent("conv-0001", 5)))
        assertFalse(sync.note(org.example.app.contracts.MembersEvent("conv-9999", 1)))
        sync.noteAll()
        sync.runPending()
        assertTrue(w.server.calls.none { it == "acc-bob/bob1 conversation" }, "no lookup for a group without a local chat")
        assertTrue(bob.repo.chats().isEmpty(), "no chat is made from a members item")
        assertTrue(bob.repo.entries(GroupSync.PENDING_PREFIX).values.all { it.isEmpty() })
    }

    @Test
    fun aListThatCannotBeTrueIsIgnoredWhole() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        val chat = f.createdGroup()
        val bob = w.device("bob1")
        val stored = bob.repo.groupMembers(chat)
        w.server.groupInfoOverride = { i ->
            ConversationInfo(i.conversationId, i.mode, i.epoch, "group", i.members + GroupMemberInfo("acc-ana", "member"), i.membersVersion!! + 1)
        }
        w.server.deliverMembers("acc-bob/bob1", "conv-0001", 1)
        val commits = w.server.commitPosts.size
        f.catchUp(bob)
        assertEquals(stored, bob.repo.groupMembers(chat))
        assertEquals(commits, w.server.commitPosts.size)
        w.server.groupInfoOverride = { i -> ConversationInfo(i.conversationId, i.mode, i.epoch, "direct") }
        w.server.deliverMembers("acc-bob/bob1", "conv-0001", 2)
        f.catchUp(bob)
        assertEquals(stored, bob.repo.groupMembers(chat))
    }

    @Test
    fun theServerNotListingThisAccountAnyMoreMakesTheChatReadOnlyWithYouWereRemovedAndDropsLaterItems() = runTest {
        val w = GroupWorld.trio()
        val f = GroupSyncFixture(w)
        val chat = f.createdGroup()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        ana.api.removeAccount("conv-0001", "acc-bob")
        f.catchUp(bob)
        assertEquals(MyState.REMOVED, bob.repo.chat(chat)!!.myState)
        assertEquals(listOf("""{"youWereRemoved":{}}"""), bob.repo.messages(chat, null, 100).filter { it.id.startsWith("sys-removed-") }.map { it.envelope.decodeToString() })
        assertTrue(bob.repo.conversations(chat).isEmpty(), "the old conversation row is gone")
        // A late message to the group (from before the server dropped his devices) is never shown and never kept.
        val record = assertNotNull(ana.repo.preferredConversation(chat))
        val messagesBefore = bob.repo.messages(chat, null, 100).size
        w.server.inject("acc-bob/bob1") { c ->
            org.example.app.contracts.InboxItem(c, "message", "conv-0001", 99, record.epoch, "acc-ana", "ana1", byteArrayOf(1, 2, 3), java.time.Instant.EPOCH)
        }
        bob.processor.sync()
        assertEquals(messagesBefore, bob.repo.messages(chat, null, 100).size)
        assertTrue(bob.repo.rawItems(10).isEmpty())
    }
}
