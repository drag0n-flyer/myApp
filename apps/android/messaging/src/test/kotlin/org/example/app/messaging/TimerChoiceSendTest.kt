package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SystemEvent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/** Choosing a disappearing-messages time in chat info (task P3): one `timer` control, one local line, then `ttl` on texts. */
class TimerChoiceSendTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private class Social : SocialPort {
        override suspend fun lookUpUser(username: String) = null
        override suspend fun block(accountId: String) = Unit
        override suspend fun unblock(accountId: String) = Unit
        override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
    }

    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private fun store(d: TestDevice) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { true }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), social = Social(), scope = scope,
    )

    @Test fun choosingSevenDaysSendsOneTimerAndTheNextTextCarriesTtl() = runTest {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val st = store(rig.bob1)

        st.setTimer("acc-ana", 604_800); st.settle()
        val sent = rig.server.messages.filter { it.sender == "acc-bob/bob1" }
        assertEquals(1, sent.size, "exactly one envelope goes out")
        assertEquals(604_800, rig.bob1.repo.timerSeconds("acc-ana"))
        assertEquals(NOW.toString(), rig.bob1.repo.get("timer-at:acc-ana")!!.decodeToString())

        // The local line: mine, so no name.
        val line = st.messages("acc-ana", null, 20).single { it.content is MessageContent.System }
        assertEquals(MessageContent.System(SystemEvent.Timer(604_800, null)), line.content)

        // The other side learns it from that one envelope.
        rig.ana1.processor.sync()
        assertEquals(604_800, rig.ana1.repo.timerSeconds("acc-bob"))

        st.send(MessageContent.Text("hello"), "acc-ana"); st.settle()
        val row = rig.bob1.repo.messages("acc-ana", null, 20).single { it.folded == "hello" }
        assertEquals(604_800, assertIs<ContentEnvelope.Text>(ContentCodec.decode(row.envelope)).ttl)
        assertNotNull(row.expiresAt)

        // Choosing the same time again adds nothing; turning it off is one more envelope and one more line.
        st.setTimer("acc-ana", 604_800); st.settle()
        assertEquals(2, rig.server.messages.count { it.sender == "acc-bob/bob1" })
        st.setTimer("acc-ana", 0); st.settle()
        assertEquals(3, rig.server.messages.count { it.sender == "acc-bob/bob1" })
        assertEquals(0, st.timerSeconds("acc-ana"))
        assertEquals(2, st.messages("acc-ana", null, 20).count { it.content is MessageContent.System })
    }

    @Test fun bothDevicesViewsExpireAtSentTimePlusTtlNotAtArrival() = runTest {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val bob = store(rig.bob1)
        rig.bob1.nowMs = NOW + 50_000 // arrives 50 s after it was sent
        rig.ana1.send(conv, ContentEnvelope.Text("m1", NOW, "hi", ttl = 100))
        rig.bob1.processor.sync()
        bob.refresh()
        val gone = mutableListOf<String>()
        val sweeper = org.example.app.core.people.ExpirySweeper(bob, { rig.bob1.nowMs }) { ids, _ -> gone += ids }

        rig.bob1.nowMs = NOW + 99_999
        assertEquals(NOW + 100_000, sweeper.sweep(), "counted from the sent time, not from arrival")
        assertNotNull(rig.bob1.repo.message("m1"))
        rig.bob1.nowMs = NOW + 100_000
        assertEquals(null, sweeper.sweep())
        assertEquals(listOf("m1"), gone)
        assertEquals(null, rig.bob1.repo.message("m1"))
        assertEquals(0, bob.chat("acc-ana")!!.messages.count { it.id == "m1" }, "the open chat's list no longer has it")
    }
}
