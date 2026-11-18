package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.GroupMemberInfo
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ChatKind
import org.example.app.messaging.ports.GroupMemberRecord

/** A welcome for a group: the chat `g:<id>`, the stored member list, "added you", and what is never joined. */
class GroupJoinTest {
    private suspend fun created(): Pair<GroupWorld, String> {
        val w = GroupWorld.trio()
        val made = w.device("ana1").groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        w.device("ana1").outbox.flush()
        return w to made.chatId
    }

    @Test
    fun aWelcomeForAGroupMakesTheGroupChatWithItsListAndTheAddedYouLine() = runTest {
        val (w, chatId) = created()
        val bob = w.device("bob1")
        bob.processor.sync()
        val chat = assertNotNull(bob.repo.chat(chatId))
        assertEquals(ChatKind.GROUP, chat.kind)
        assertEquals(false, chat.isRequest)
        assertEquals("conv-0001", chat.conversationId)
        assertEquals(
            listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member"), GroupMemberRecord("acc-cat", "member")),
            bob.repo.groupMembers(chatId),
        )
        assertNull(bob.repo.chat("acc-ana"), "no one-to-one chat with the creator")
        assertEquals(listOf(chatId), bob.repo.chats().map { it.id })
        val line = bob.repo.messages(chatId, null, 20).single { it.id.startsWith("sys-added-conv-0001") }
        assertEquals("""{"addedYou":{"name":"acc-ana"}}""", line.envelope.decodeToString())
        val conversation = assertNotNull(bob.repo.preferredConversation(chatId))
        assertTrue(conversation.active)
        assertEquals("e2ee", conversation.mode)
        assertEquals("e2ee", conversation.innerMode, "the mode pin follows the creator's envelope as in a one-to-one chat")
        assertEquals(0, bob.repo.chat(chatId)!!.unread)
    }

    @Test
    fun theNameComesFromTheOwnersGroupEnvelope() = runTest {
        val (w, chatId) = created()
        val bob = w.device("bob1")
        bob.processor.sync()
        assertEquals("Book club", bob.repo.chat(chatId)!!.title)
        assertEquals(1, bob.repo.chat(chatId)!!.groupVer)
    }

    @Test
    fun aGroupEnvelopeFromAPlainMemberIsIgnored() = runTest {
        val (w, chatId) = created()
        val bob = w.device("bob1")
        val cat = w.device("cat1")
        bob.processor.sync()
        cat.processor.sync()
        val record = assertNotNull(bob.repo.preferredConversation(chatId))
        bob.send(record, ContentEnvelope.Group("g-evil", NOW, "Mine now", null, 9))
        cat.processor.sync()
        assertEquals("Book club", cat.repo.chat(chatId)!!.title)
        assertEquals(1, cat.repo.chat(chatId)!!.groupVer)
        assertTrue(cat.repo.messages(chatId, null, 50).none { it.id == "g-evil" })
    }

    @Test
    fun aWelcomeForAGroupTheServerSaysThisAccountIsNotInIsDropped() = runTest {
        val (w, _) = created()
        w.server.removeGroupAccount("conv-0001", "acc-bob")
        val bob = w.device("bob1")
        bob.processor.sync()
        assertTrue(bob.repo.chats().none { it.kind == ChatKind.GROUP })
        assertTrue(bob.repo.conversations("g:conv-0001").isEmpty())
        assertTrue(bob.repo.rawItems(10).isEmpty(), "dropped, not retried for ever")
    }

    @Test
    fun aListWithoutThisAccountIsDroppedWithoutAChat() = runTest {
        val (w, _) = created()
        w.server.groupInfoOverride = { it.withMembers(it.members.filter { m -> m.account != "acc-bob" }) }
        val bob = w.device("bob1")
        bob.processor.sync()
        assertTrue(bob.repo.chats().isEmpty())
        assertTrue(bob.repo.rawItems(10).isEmpty())
        assertTrue(bob.repo.conversations("g:conv-0001").isEmpty())
    }

    @Test
    fun aHostileListIsNeverStored() = runTest {
        for (bad in listOf<(ConversationInfo) -> ConversationInfo>(
            { it.withMembers(it.members + (1..100).map { n -> GroupMemberInfo("acc-x$n", "member") }) },
            { it.withMembers(it.members.map { m -> if (m.account == "acc-cat") GroupMemberInfo(m.account, "root") else m }) },
            { it.withMembers(it.members + it.members.first()) },
            { it.withMembers(it.members + GroupMemberInfo("", "member")) },
        )) {
            val (w, _) = created()
            w.server.groupInfoOverride = bad
            val bob = w.device("bob1")
            bob.processor.sync()
            assertTrue(bob.repo.chats().isEmpty())
            assertTrue(bob.repo.rawItems(10).isEmpty())
        }
    }

    @Test
    fun aSecondWelcomeForAGroupAlreadyJoinedIsDroppedAndTheChatKeepsItsChoices() = runTest {
        val (w, chatId) = created()
        val bob = w.device("bob1")
        bob.processor.sync()
        bob.repo.setMuted(chatId, true)
        val before = bob.repo.conversations(chatId).single()
        // The same conversation ID arrives again in another welcome (a hostile or duplicated one).
        w.server.deliverWelcomeCopy("acc-bob/bob1", "conv-0001")
        bob.processor.sync()
        assertEquals(listOf(chatId), bob.repo.chats().map { it.id })
        assertEquals(true, bob.repo.chat(chatId)!!.muted)
        assertEquals(before.groupId.toList(), bob.repo.conversations(chatId).single().groupId.toList())
        assertTrue(bob.repo.rawItems(10).isEmpty())
    }

    @Test
    fun aDirectWelcomeStillMakesAOneToOneChatWithThePeerAccountAsItsId() = runTest {
        val rig = Rig().start()
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        assertEquals(listOf("acc-ana"), rig.bob1.repo.chats().map { it.id })
        assertEquals(ChatKind.DIRECT, rig.bob1.repo.chat("acc-ana")!!.kind)
    }

    @Test
    fun aSecondDeviceOfAMemberIsAddedByThatMemberOnly() = runTest {
        // none-bob2 has no KeyPackage when the group is made: only bob1 is added.
        val w = GroupWorld(mapOf("acc-ana" to listOf("ana1"), "acc-bob" to listOf("bob1", "none-bob2"), "acc-cat" to listOf("cat1"))).start()
        val ana = w.device("ana1")
        val bob1 = w.device("bob1")
        val bob2 = w.device("none-bob2")
        ana.groups.create(listOf("acc-bob", "acc-cat"), "Trio", null)
        val chatId = "g:conv-0001"
        bob1.processor.sync()
        w.device("cat1").processor.sync()
        // The new device now has KeyPackages. The creator's device sync adds only the creator's own devices.
        bob2.api.uploadKeyPackages(bob2.mls.makeKeyPackages(2))
        runCatching { ana.deviceSync.reconcileOwnDevices() }
        assertTrue(w.server.conversation("conv-0001")!!.members.none { it == "acc-bob/none-bob2" }, "ana never adds bob's device")
        bob1.deviceSync.reconcileOwnDevices()
        assertTrue(w.server.conversation("conv-0001")!!.members.contains("acc-bob/none-bob2"))
        bob2.processor.sync()
        assertEquals(listOf(chatId), bob2.repo.chats().map { it.id })
        assertNotNull(bob2.repo.preferredConversation(chatId))
    }
}

private fun ConversationInfo.withMembers(members: List<GroupMemberInfo>) =
    ConversationInfo(conversationId, mode, epoch, kind, members, membersVersion)
