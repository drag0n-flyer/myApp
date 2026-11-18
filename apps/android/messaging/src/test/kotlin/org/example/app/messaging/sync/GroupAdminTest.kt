package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.GroupChangeFailed
import org.example.app.core.chats.GroupChangeFailed.Reason
import org.example.app.core.groups.GroupRole
import org.example.app.messaging.TestJpeg
import org.example.app.messaging.ports.MyState

private val admins = java.util.WeakHashMap<TestDevice, GroupAdmin>()

/** A [GroupAdmin] for one device of the fixture's world. */
internal fun GroupSyncFixture.admin(d: TestDevice): GroupAdmin =
    admins.getOrPut(d) { GroupAdmin(d.me, d.api, d.repo, d.clock, of(d), d.outbox) }

private fun failure(block: suspend () -> Unit): Reason = kotlinx.coroutines.runBlocking { assertFailsWith<GroupChangeFailed> { block() }.reason }

class GroupAdminTest {
    private fun world() = GroupWorld(
        mapOf(
            "acc-ana" to listOf("ana1"), "acc-bob" to listOf("bob1", "bob2"), "acc-cat" to listOf("cat1"), "acc-dan" to listOf("dan1"),
            "acc-eve" to listOf("eve1"), "acc-fay" to listOf("none-fay1"),
        ),
    )

    private suspend fun group(): Triple<GroupWorld, GroupSyncFixture, String> {
        val w = world().start()
        val f = GroupSyncFixture(w)
        val ana = w.device("ana1")
        val chat = ana.groups.create(listOf("acc-bob", "acc-cat", "acc-dan"), "Book club", null).chatId
        ana.outbox.flush()
        for (d in w.all.filter { it.me.accountId != "acc-eve" && it.me.accountId != "acc-fay" }) f.catchUp(d)
        return Triple(w, f, chat)
    }

    @Test
    fun addingAnAccountClaimsItsKeyPackagesCommitsWithAWelcomeAndTheNewMemberJoins() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        assertEquals(emptyList(), f.admin(ana).addMembers(chat, listOf("acc-eve")))
        assertTrue(w.server.conversation("conv-0001")!!.members.contains("acc-eve/eve1"))
        ana.outbox.flush()
        val eve = w.device("eve1")
        f.catchUp(eve)
        assertEquals(listOf("acc-ana", "acc-bob", "acc-cat", "acc-dan", "acc-eve"), eve.repo.groupMembers(chat).map { it.accountId })
        assertEquals("Book club", eve.repo.chat(chat)!!.title)
        assertEquals(listOf("""{"groupAdded":{"byYou":true,"who":"acc-eve"}}"""), ana.lines(chat))
        f.catchUp(w.device("bob1"))
        assertEquals(listOf("""{"groupAdded":{"by":"acc-ana","who":"acc-eve"}}"""), w.device("bob1").lines(chat))
    }

    @Test
    fun anAccountWithNoDeviceReadyIsReportedAsNotAddedYet() = runTest {
        val (w, f, chat) = group()
        assertEquals(listOf("acc-fay"), f.admin(w.device("ana1")).addMembers(chat, listOf("acc-fay")))
        assertTrue("acc-fay" in w.device("ana1").repo.groupMembers(chat).map { it.accountId })
    }

    @Test
    fun theServersRefusalsAreNamedAndAPlainMemberDoesNotEvenAsk() = runTest {
        val (w, f, chat) = group()
        assertEquals(Reason.NotFound, failure { f.admin(w.device("ana1")).addMembers(chat, listOf("acc-nobody")) })
        assertEquals(Reason.Forbidden, failure { f.admin(w.device("bob1")).addMembers(chat, listOf("acc-eve")) })
        assertTrue(w.server.calls.none { it == "acc-bob/bob1 addAccounts" })
        w.server.conversation("conv-0001")!!.let { c -> (1..95).forEach { c.accounts["acc-x$it"] = "member" } }
        assertEquals(Reason.Full, failure { f.admin(w.device("ana1")).addMembers(chat, listOf("acc-eve", "acc-fay")) })
    }

    @Test
    fun removingAMemberTakesItsDevicesOutOfTheMlsGroupAndRolesDecideWhoMay() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        f.admin(ana).setRole(chat, "acc-bob", GroupRole.Admin)
        f.catchUp(w.device("bob1")); f.catchUp(w.device("bob2"))
        // An admin removes a member, but not the owner and not (as a member would) anyone.
        assertEquals(Reason.Forbidden, failure { f.admin(w.device("bob1")).removeMember(chat, "acc-ana") })
        assertEquals(Reason.Forbidden, failure { f.admin(w.device("cat1")).removeMember(chat, "acc-dan") })
        f.admin(w.device("bob1")).removeMember(chat, "acc-dan")
        assertTrue(w.device("bob1").groupIdentities(chat).none { it.startsWith("acc-dan/") })
        assertTrue(w.server.conversation("conv-0001")!!.members.none { it.startsWith("acc-dan/") })
        f.catchUp(ana)
        assertTrue(ana.groupIdentities(chat).none { it.startsWith("acc-dan/") })
        assertEquals(Reason.NotFound, failure { f.admin(ana).removeMember(chat, "acc-dan") })
    }

    @Test
    fun aMemberLeavesTheChatIsReadOnlyWithNoRemovedLineAndTheOthersRemoveItsDevices() = runTest {
        val (w, f, chat) = group()
        val cat = w.device("cat1")
        f.admin(cat).leave(chat)
        assertEquals(MyState.LEFT, cat.repo.chat(chat)!!.myState)
        assertTrue(cat.repo.conversations(chat).isEmpty())
        assertEquals(listOf("""{"groupLeft":{}}"""), cat.repo.messages(chat, null, 100).filter { it.id.startsWith("sys-gone-") }.map { it.envelope.decodeToString() })
        assertEquals(Reason.Forbidden, failure { f.admin(cat).rename(chat, "Mine") })
        f.catchUp(cat) // its own members item: nothing happens to a chat it left
        assertEquals(MyState.LEFT, cat.repo.chat(chat)!!.myState)
        f.catchUp(w.device("bob1"))
        assertEquals(listOf("""{"groupLeft":{"who":"acc-cat"}}"""), w.device("bob1").lines(chat))
        assertTrue(w.device("bob1").groupIdentities(chat).none { it.startsWith("acc-cat/") })
    }

    @Test
    fun theOwnerMustChooseANewOwnerBeforeLeavingAndThenMay() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        assertEquals(Reason.OwnerMustTransfer, failure { f.admin(ana).leave(chat) })
        assertTrue(w.server.calls.none { it == "acc-ana/ana1 removeAccount" })
        // The server says the same when this device's list is behind: the call is refused with the same reason.
        val cat = w.device("cat1")
        w.server.onCall = { _, call -> if (call == "removeAccount") throw org.example.app.contracts.ServerException(409, "owner_must_transfer", null, null) }
        assertEquals(Reason.OwnerMustTransfer, failure { f.admin(cat).leave(chat) })
        assertEquals(MyState.MEMBER, cat.repo.chat(chat)!!.myState, "nothing changed on this device")
        w.server.onCall = null
        f.admin(ana).setRole(chat, "acc-bob", GroupRole.Owner)
        assertEquals(GroupRole.Admin.wire, w.server.conversation("conv-0001")!!.accounts["acc-ana"])
        f.admin(ana).leave(chat)
        assertEquals(MyState.LEFT, ana.repo.chat(chat)!!.myState)
        f.catchUp(w.device("bob1"))
        assertEquals("owner", w.device("bob1").repo.groupMembers(chat).first { it.accountId == "acc-bob" }.role)
    }

    @Test
    fun onlyTheOwnerSetsRolesAndTheOthersSeeTheLine() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        assertEquals(Reason.Forbidden, failure { f.admin(w.device("bob1")).setRole(chat, "acc-cat", GroupRole.Admin) })
        assertEquals(Reason.Forbidden, failure { f.admin(ana).setRole(chat, "acc-ana", GroupRole.Member) })
        f.admin(ana).setRole(chat, "acc-cat", GroupRole.Admin)
        f.catchUp(w.device("bob1"))
        assertEquals(listOf("""{"madeAdmin":{"who":"acc-cat"}}"""), w.device("bob1").lines(chat))
        f.admin(ana).setRole(chat, "acc-cat", GroupRole.Member)
        f.catchUp(w.device("bob1"))
        assertEquals(2, w.device("bob1").lines(chat).size)
        assertTrue(w.device("bob1").lines(chat).last().startsWith("""{"noLongerAdmin""""))
    }

    @Test
    fun renamingGivesTheNextVersionAMemberCannotAndReceiversApplyItWithALine() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        f.catchUp(bob)
        assertEquals(Reason.Forbidden, failure { f.admin(bob).rename(chat, "Mine") })
        assertEquals(Reason.Other, failure { f.admin(ana).rename(chat, "   ") })
        f.admin(ana).rename(chat, " Readers ")
        assertEquals("Readers", ana.repo.chat(chat)!!.title)
        assertEquals(2, ana.repo.chat(chat)!!.groupVer)
        ana.outbox.flush()
        bob.processor.sync()
        assertEquals("Readers", bob.repo.chat(chat)!!.title)
        assertEquals(2, bob.repo.chat(chat)!!.groupVer)
        val lines = bob.repo.messages(chat, null, 100).filter { it.id.startsWith("sys-group-") }.map { it.envelope.decodeToString() }
        assertEquals(listOf("""{"groupRenamed":{"by":"acc-ana","name":"Readers"}}"""), lines)
        assertEquals(listOf("""{"groupRenamed":{"name":"Readers"}}"""), ana.repo.messages(chat, null, 100).filter { it.id.startsWith("sys-group-") }.map { it.envelope.decodeToString() })
    }

    @Test
    fun theHigherVersionNameWinsWhateverTheOrderAndAnOlderOneChangesNothing() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        f.admin(ana).setRole(chat, "acc-bob", GroupRole.Admin)
        f.catchUp(bob)
        f.admin(ana).rename(chat, "One") // ver 2
        f.admin(ana).rename(chat, "Two") // ver 3
        ana.outbox.flush()
        val cat = w.device("cat1")
        cat.processor.sync()
        assertEquals("Two", cat.repo.chat(chat)!!.title)
        // A late ver 2 from the other admin does not undo it.
        f.admin(bob).rename(chat, "Late") // bob never saw ver 2 or 3 yet? he has: his own version is built on his stored one
        bob.outbox.flush()
        cat.processor.sync()
        assertTrue(cat.repo.chat(chat)!!.groupVer!! >= 3)
    }

    @Test
    fun twoAdminsRenamingAtTheSameMomentEveryoneEndsOnTheGreaterNameInEitherArrivalOrder() = runTest {
        for (anaFirst in listOf(true, false)) {
            val (w, f, chat) = group()
            val ana = w.device("ana1")
            val bob = w.device("bob1")
            f.admin(ana).setRole(chat, "acc-bob", GroupRole.Admin)
            f.catchUp(bob)
            for (d in listOf("bob2", "cat1", "dan1")) f.catchUp(w.device(d))
            f.admin(ana).rename(chat, "Alpha") // both build ver 2 before seeing the other
            f.admin(bob).rename(chat, "Beta")
            if (anaFirst) { ana.outbox.flush(); bob.outbox.flush() } else { bob.outbox.flush(); ana.outbox.flush() }
            for (d in w.all) d.processor.sync()
            for (d in w.all.filter { it.repo.chat(chat) != null }) assertEquals("Beta", d.repo.chat(chat)!!.title, "${d.me.identity} anaFirst=$anaFirst")
        }
    }

    @Test
    fun aPhotoChangeSendsTheGroupEnvelopeWithTheNewPhotoAndGivesTheLine() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        val photo = TestJpeg.of()
        assertEquals(Reason.Other, failure { f.admin(ana).setGroupPhotoBad(chat) })
        f.admin(ana).setPhoto(chat, photo)
        ana.outbox.flush()
        bob.processor.sync()
        assertTrue(photo.contentEquals(bob.repo.chat(chat)!!.photo))
        assertEquals(2, bob.repo.chat(chat)!!.groupVer)
        assertEquals(
            listOf("""{"groupPhoto":{"by":"acc-ana"}}"""),
            bob.repo.messages(chat, null, 100).filter { it.id.startsWith("sys-group-") }.map { it.envelope.decodeToString() },
        )
        f.admin(ana).setPhoto(chat, null)
        ana.outbox.flush()
        bob.processor.sync()
        assertEquals(null, bob.repo.chat(chat)!!.photo)
    }

    @Test
    fun aRemovedMemberIsReadOnlyDropsLaterItemsKeepsNothingParkedAndCanBeAddedAgain() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        f.admin(ana).removeMember(chat, "acc-bob")
        f.catchUp(bob)
        assertEquals(MyState.REMOVED, bob.repo.chat(chat)!!.myState)
        assertEquals(Reason.Forbidden, failure { f.admin(bob).rename(chat, "Mine") })
        // A late message and a late commit for that conversation: dropped before the engine, nothing shown, nothing parked.
        for (kind in listOf("message", "commit")) {
            w.server.inject("acc-bob/bob1") { c ->
                org.example.app.contracts.InboxItem(c, kind, "conv-0001", 99, 3, "acc-ana", "ana1", byteArrayOf(1, 2, 3), java.time.Instant.EPOCH)
            }
        }
        bob.processor.sync()
        assertTrue(bob.repo.entries(Parked.PREFIX).values.all { it.isEmpty() })
        assertTrue(bob.repo.rawItems(10).isEmpty())
        assertTrue(bob.repo.messages(chat, null, 100).none { it.senderAccount == "acc-ana" })
        // Added again (the engine still holds the old state: it must replace it, see FakeMlsEngine.welcomeReplacesGroup): the Welcome is not refused as rebound, the chat is a member chat again.
        bob.engine.welcomeReplacesGroup = true
        w.device("bob2").engine.welcomeReplacesGroup = true
        assertEquals(emptyList(), f.admin(ana).addMembers(chat, listOf("acc-bob")))
        f.catchUp(bob)
        assertEquals(MyState.MEMBER, bob.repo.chat(chat)!!.myState)
        assertNotNull(bob.repo.preferredConversation(chat))
        assertTrue(bob.repo.messages(chat, null, 100).none { it.id.startsWith("join-failed") })
    }

    @Test
    fun aGroupWelcomeTheEngineRefusesIsOnlyParkedNeverAOneToOneCouldntJoinChat() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        f.admin(ana).removeMember(chat, "acc-bob")
        f.catchUp(bob)
        // The engine still holds the old state and refuses the new Welcome as a duplicate (the default of the fake).
        f.admin(ana).addMembers(chat, listOf("acc-bob"))
        f.catchUp(bob)
        assertTrue(bob.repo.entries(Parked.PREFIX).values.any { it.isNotEmpty() }, "parked")
        assertNull(bob.repo.chat("acc-ana"), "no chat with the creator")
        assertTrue(bob.repo.chats().none { it.kind != org.example.app.messaging.ports.ChatKind.GROUP })
        assertTrue(bob.repo.messages(chat, null, 100).none { it.id.startsWith("join-failed") })
        assertEquals(MyState.REMOVED, bob.repo.chat(chat)!!.myState)
    }

    @Test
    fun aLeftoverActorHintNeverTurnsAnAdminsRemovalIntoALeave() = runTest {
        val (w, f, chat) = group()
        val ana = w.device("ana1")
        val bob = w.device("bob1")
        f.of(bob).hint("conv-0001", 1, "acc-bob") // a hint from an earlier pass that never finished
        f.admin(ana).removeMember(chat, "acc-cat")
        f.admin(ana).removeMember(chat, "acc-bob")
        f.of(bob).noteAll()
        f.of(bob).runPending()
        assertEquals(MyState.REMOVED, bob.repo.chat(chat)!!.myState)
        assertEquals(listOf("""{"youWereRemoved":{}}"""), bob.repo.messages(chat, null, 100).filter { it.id.startsWith("sys-gone-") }.map { it.envelope.decodeToString() })
    }

    @Test
    fun deletingTheChatRemovesItsRows() = runTest {
        val (w, f, chat) = group()
        val bob = w.device("bob1")
        f.admin(w.device("ana1")).removeMember(chat, "acc-bob")
        f.catchUp(bob)
        bob.repo.deleteChat(chat)
        assertEquals(null, bob.repo.chat(chat))
        assertEquals(emptyList(), bob.repo.groupMembers(chat))
        assertEquals(emptyList(), bob.repo.messages(chat, null, 10))
    }
}

private suspend fun GroupAdmin.setGroupPhotoBad(chat: String) = setPhoto(chat, byteArrayOf(1, 2, 3, 4))
