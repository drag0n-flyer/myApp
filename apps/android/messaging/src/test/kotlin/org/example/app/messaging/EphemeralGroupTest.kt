package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.ServerEvent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.GroupMemberRecord
import org.example.app.messaging.sync.GroupWorld
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.TestDevice

/** Typing inside a group: from a listed member only, never presence, never from me or a blocked member. */
class EphemeralGroupTest {
    private val chatId = "g:conv-0001"

    private class Setup(val w: GroupWorld, val bob: TestDevice, val channel: EphemeralChannel, val sent: MutableList<String>)

    private suspend fun setup(): Setup {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        ana.groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        ana.outbox.flush()
        for (id in listOf("bob1", "cat1")) w.device(id).processor.sync()
        val bob = w.device("bob1")
        val sent = mutableListOf<String>()
        return Setup(w, bob, EphemeralChannel(bob.me, bob.repo) { conv, _, _ -> sent += conv }, sent)
    }

    private fun typing(on: Boolean = true) = ContentCodec.encode(ContentEnvelope.Typing("t1", NOW, on))
    private fun presence() = ContentCodec.encode(ContentEnvelope.Presence("p1", NOW, "online"))

    private suspend fun Setup.receive(from: String, bytes: ByteArray, conversation: String = "conv-0001"): EphemeralEvent? {
        val got = mutableListOf<EphemeralEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { channel.events.toList(got) }
        channel.onFrame(ServerEvent.ConvEphemeral(conversation, 0, from, "dev", bytes))
        job.cancel()
        return got.singleOrNull()
    }

    @Test
    fun typingFromAListedMemberReachesTheGroupWithTheirAccount() = runTest {
        val s = setup()
        val event = s.receive("acc-cat", typing())!!
        assertEquals(chatId, event.chatId)
        assertEquals("acc-cat", event.accountId)
    }

    @Test
    fun presenceInAGroupIsDropped() = runTest {
        val s = setup()
        assertNull(s.receive("acc-cat", presence()))
    }

    @Test
    fun typingFromMeAFrameFromANonMemberAndABlockedMemberIsDropped() = runTest {
        val s = setup()
        assertNull(s.receive("acc-bob", typing()), "my own account")
        assertNull(s.receive("acc-eve", typing()), "not in the list")
        s.bob.repo.block("acc-cat", NOW)
        assertNull(s.receive("acc-cat", typing()), "blocked")
    }

    @Test
    fun aMemberRemovedFromTheStoredListIsDropped() = runTest {
        val s = setup()
        s.bob.repo.setGroupMembers(chatId, listOf(GroupMemberRecord("acc-ana", "owner"), GroupMemberRecord("acc-bob", "member")), 5)
        assertNull(s.receive("acc-cat", typing()))
    }

    @Test
    fun anUnknownConversationIsDropped() = runTest {
        val s = setup()
        assertNull(s.receive("acc-cat", typing(), conversation = "conv-9999"))
    }

    @Test
    fun myTypingGoesToTheGroupsConversation() = runTest {
        val s = setup()
        assertEquals(true, s.channel.send(chatId, ContentEnvelope.Typing("t2", NOW, true)))
        assertEquals(listOf("conv-0001"), s.sent)
    }
}
