package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.NotificationOutcome
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SystemEvent
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

private val DAY = 86_400
private val JPEG = byteArrayOf(-1, -40, 1, 2, 3)

/** Disappearing messages, timers and profiles through the real store, handler and notification path (task P1). */
class PeopleBaseTest {
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

    private class Setup(val rig: Rig, val conv: ConversationRecord) {
        val bob get() = rig.bob1
        val ana get() = rig.ana1
    }

    private suspend fun setup(): Setup {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return Setup(rig, conv)
    }

    private suspend fun Setup.deliver(env: ContentEnvelope) {
        ana.send(conv, env)
        bob.processor.sync()
    }

    private fun Setup.chatMessages() = bob.repo.messages("acc-ana", null, 50)

    // Expiry on arrival

    @Test fun incomingTtlStoresExpiresAt() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.Text("m1", NOW, "hi", ttl = DAY))
        assertEquals(NOW + DAY * 1000L, s.bob.repo.message("m1")!!.expiresAt)
        s.deliver(ContentEnvelope.Text("m2", NOW, "no timer"))
        assertNull(s.bob.repo.message("m2")!!.expiresAt)
    }

    @Test fun aSenderSuppliedFutureAtDoesNotExtendALifetime() = runTest {
        val s = setup()
        val future = NOW + 10 * DAY * 1000L
        s.deliver(ContentEnvelope.Text("f1", future, "from the future", ttl = 60))
        assertEquals(NOW + 60_000, s.bob.repo.message("f1")!!.expiresAt, "counted from the receive time, not the claimed time")
    }

    @Test fun sweepingAnUnreadMessageKeepsTheUnreadCountRight() = runTest {
        val s = setup()
        val st = store(s.bob)
        s.deliver(ContentEnvelope.Text("a", NOW, "one", ttl = 100))
        s.deliver(ContentEnvelope.Text("b", NOW, "two", ttl = 100))
        s.deliver(ContentEnvelope.Text("c", NOW, "three"))
        assertEquals(3, s.bob.repo.chat("acc-ana")!!.unread)
        assertEquals(setOf("a", "b"), st.sweepExpired(NOW + 100_000).toSet())
        assertEquals(1, s.bob.repo.chat("acc-ana")!!.unread)
        assertEquals(1, st.chat("acc-ana")!!.unreadCount, "the cache shows it too")
    }

    @Test fun aMessageAlreadyExpiredOnArrivalIsDroppedBeforeInsert() = runTest {
        val s = setup()
        s.bob.nowMs = NOW + 60_000 // the device was offline for a minute
        s.deliver(ContentEnvelope.Text("late", NOW, "gone", ttl = 60)) // expires exactly now: expired
        s.deliver(ContentEnvelope.Text("later", NOW - 10_000, "gone too", ttl = 5))
        s.deliver(ContentEnvelope.Text("fine", NOW, "kept", ttl = 61))
        assertNull(s.bob.repo.message("late"))
        assertNull(s.bob.repo.message("later"))
        assertNotNull(s.bob.repo.message("fine"))
        assertEquals(1, s.bob.repo.chat("acc-ana")!!.unread, "only the live message counts")
    }

    @Test fun anExpiredAttachmentMessageStoresNoAttachmentRecord() = runTest {
        val s = setup()
        s.bob.nowMs = NOW + 120_000
        val att = Att("att-x", ByteArray(32), ByteArray(32), 10, 26)
        s.deliver(ContentEnvelope.Photo("p1", NOW, att, 4, 3, ttl = 60))
        assertNull(s.bob.repo.message("p1"))
        assertNull(s.bob.repo.attachment("att-x"))
        s.deliver(ContentEnvelope.Photo("p2", NOW + 100_000, Att("att-y", att.key, att.dg, att.sz, att.csz), 4, 3, ttl = 60, c = "A caption"))
        assertEquals("A caption", s.bob.repo.message("p2")!!.caption)
        assertNotNull(s.bob.repo.attachment("att-y"))
        assertEquals(listOf("p2"), s.bob.repo.search("caption", 5).map { it.messageId })
    }

    @Test fun notificationPathStoresExpiresAtAndSkipsExpired() = runTest {
        val s = setup()
        val np = s.bob.notifications()
        suspend fun push(env: ContentEnvelope): NotificationOutcome {
            s.ana.send(s.conv, env)
            val item = s.rig.server.pending("acc-bob/bob1").last()
            return s.bob.lock.withLock { np.onMessagePush(pad(item.payload)) }
        }
        assertIs<NotificationOutcome.Message>(push(ContentEnvelope.Text("n1", NOW, "live", ttl = 600)))
        assertEquals(NOW + 600_000, s.bob.repo.message("n1")!!.expiresAt)
        s.bob.nowMs = NOW + 700_000
        assertIs<NotificationOutcome.Nothing>(push(ContentEnvelope.Text("n2", NOW, "expired", ttl = 600)))
        assertNull(s.bob.repo.message("n2"))
    }

    // Timer

    @Test fun timerEnvelopeSetsTheChatTimerAndAddsOneSystemLine() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.Timer("t1", NOW, 604_800))
        assertEquals(604_800, s.bob.repo.timerSeconds("acc-ana"))
        assertEquals(0, s.bob.repo.chat("acc-ana")!!.unread, "a timer change never counts as unread")
        val line = s.chatMessages().single()
        assertEquals("system", line.senderAccount)
        val ui = store(s.bob).chat("acc-ana")!!.messages.single()
        assertEquals(MessageContent.System(SystemEvent.Timer(604_800, "Ana")), ui.content)

        s.deliver(ContentEnvelope.Timer("t2", NOW + 1, 604_800)) // no change, no second line
        assertEquals(1, s.chatMessages().size)
        s.deliver(ContentEnvelope.Timer("t3", NOW + 2, 0))
        assertEquals(0, s.bob.repo.timerSeconds("acc-ana"))
        assertEquals(2, s.chatMessages().size)
        // A late older timer does not undo the newer one.
        s.deliver(ContentEnvelope.Timer("t0", NOW - 5, 86_400))
        assertEquals(0, s.bob.repo.timerSeconds("acc-ana"))
    }

    @Test fun timerNeverNotifies() = runTest {
        val s = setup()
        s.ana.send(s.conv, ContentEnvelope.Timer("t1", NOW, DAY))
        val item = s.rig.server.pending("acc-bob/bob1").last()
        val outcome = s.bob.lock.withLock { s.bob.notifications().onMessagePush(pad(item.payload)) }
        assertIs<NotificationOutcome.Nothing>(outcome)
        assertEquals(DAY, s.bob.repo.timerSeconds("acc-ana"))
    }

    // Profile

    @Test fun profileIsMergedByVersionAndNeverShownAsAMessage() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.Profile("p1", NOW, "Ann", JPEG, 2))
        var p = s.bob.repo.profile("acc-ana")!!
        assertEquals("Ann", p.name); assertContentEquals(JPEG, p.photo); assertEquals(2, p.ver)

        s.deliver(ContentEnvelope.Profile("p2", NOW + 1, "Same ver", null, 2))
        s.deliver(ContentEnvelope.Profile("p3", NOW + 2, "Lower ver", null, 1))
        p = s.bob.repo.profile("acc-ana")!!
        assertEquals("Ann", p.name); assertContentEquals(JPEG, p.photo)

        s.deliver(ContentEnvelope.Profile("p4", NOW + 3, "Anna", null, 3))
        assertEquals("Anna", s.bob.repo.profile("acc-ana")!!.name)
        assertNull(s.bob.repo.profile("acc-ana")!!.photo)

        assertTrue(s.chatMessages().isEmpty(), "no chat row for a profile")
        assertEquals(0, s.bob.repo.chat("acc-ana")!!.unread)
    }

    @Test fun badProfilesAreIgnoredWhole() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.Profile("p1", NOW, "Ann", null, 1))
        // Raw envelopes the typed constructors would not build: a name that fails the rule, a too-big photo, ver 0.
        for (json in listOf(
            """{"v":1,"id":"b1","at":$NOW,"k":"profile","name":" padded ","ver":9}""",
            """{"v":1,"id":"b2","at":$NOW,"k":"profile","name":"Eve","ver":9,"photo":"${java.util.Base64.getEncoder().encodeToString(ByteArray(24_577) { if (it == 0) -1 else if (it == 1) -40 else 0 })}"}""",
            """{"v":1,"id":"b3","at":$NOW,"k":"profile","name":"Eve","ver":0}""",
        )) {
            s.ana.send(s.conv, ContentCodec.decode(json.encodeToByteArray()).let { ContentEnvelope.Unknown(it.id, it.at, "profile", json) })
            s.bob.processor.sync()
        }
        assertEquals("Ann", s.bob.repo.profile("acc-ana")!!.name)
        assertEquals(1, s.bob.repo.profile("acc-ana")!!.ver)
        assertTrue(s.chatMessages().isEmpty())
        assertEquals(0, s.bob.repo.chat("acc-ana")!!.unread)
    }

    // Typing and presence as stored messages

    @Test fun strayTypingAndPresenceAreDroppedWithoutRowOrNotification() = runTest {
        val s = setup()
        val np = s.bob.notifications()
        val raw = listOf(
            ContentEnvelope.Typing("ty1", NOW, true),
            ContentEnvelope.Presence("pr1", NOW, "online"),
            ContentEnvelope.Unknown("ty2", NOW, "typing", """{"v":1,"id":"ty2","at":$NOW,"k":"typing","on":"yes"}"""),
            ContentEnvelope.Unknown("pr2", NOW, "presence", """{"v":1,"id":"pr2","at":$NOW,"k":"presence","state":"busy"}"""),
            ContentEnvelope.Unknown("pf2", NOW, "profile", """{"v":1,"id":"pf2","at":$NOW,"k":"profile"}"""),
            ContentEnvelope.Unknown("tm2", NOW, "timer", """{"v":1,"id":"tm2","at":$NOW,"k":"timer","seconds":60}"""),
        )
        for (env in raw) {
            s.ana.send(s.conv, env)
            val item = s.rig.server.pending("acc-bob/bob1").last()
            assertIs<NotificationOutcome.Nothing>(s.bob.lock.withLock { np.onMessagePush(pad(item.payload)) }, env.id)
        }
        assertTrue(s.chatMessages().isEmpty())
        assertEquals(0, s.bob.repo.chat("acc-ana")!!.unread)
        assertEquals(0, s.bob.repo.timerSeconds("acc-ana"))
        // An unknown kind still becomes an unsupported row, as before.
        s.deliver(ContentEnvelope.Unknown("u1", NOW, "sticker", """{"v":1,"id":"u1","at":$NOW,"k":"sticker"}"""))
        assertEquals(listOf("u1"), s.chatMessages().map { it.id })
    }

    // Send path

    @Test fun aTimerOnTheChatPutsTtlAndExpiresAtOnEverySend() = runTest {
        val s = setup()
        val st = store(s.bob)
        assertEquals(0, st.timerSeconds("acc-ana"))
        st.send(MessageContent.Text("before"), "acc-ana"); st.settle()
        assertNull(s.bob.repo.messages("acc-ana", null, 10).single { it.senderAccount == "acc-bob" }.expiresAt)

        st.setTimer("acc-ana", 604_800); st.settle()
        assertEquals(604_800, st.timerSeconds("acc-ana"))
        s.bob.nowMs = NOW + 5_000
        st.send(MessageContent.Text("after"), "acc-ana"); st.settle()
        val row = s.bob.repo.messages("acc-ana", null, 10).single { it.folded == "after" }
        assertEquals(row.sentAt + 604_800_000L, row.expiresAt)
        val env = assertIs<ContentEnvelope.Text>(ContentCodec.decode(row.envelope))
        assertEquals(604_800, env.ttl)
        val posted = s.rig.server.messages.last { it.sender == "acc-bob/bob1" }
        assertEquals(row.expiresAt, posted.expiresAtMs, "the HTTP send carries expiresAt")
        val first = s.rig.server.messages.first { it.sender == "acc-bob/bob1" }
        assertNull(first.expiresAtMs)
    }

    @Test fun anExpiredMessageStillInTheOutboxIsNotSent() = runTest {
        val s = setup()
        val st = store(s.bob)
        st.setTimer("acc-ana", DAY); st.settle()
        s.bob.nowMs = NOW + 1
        s.rig.server.failNextMessages = 100 // the first tries fail, so the message waits
        st.send(MessageContent.Text("waiting"), "acc-ana"); st.settle()
        val before = s.rig.server.messages.size
        s.bob.nowMs = NOW + DAY * 1000L + 10_000
        s.rig.server.failNextMessages = 0
        st.outbox.flush()
        assertEquals(before, s.rig.server.messages.size)
        assertTrue(s.bob.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
    }

    @Test fun overlongCaptionIsRefusedOnSend() = runTest {
        val s = setup()
        val att = Att("a", ByteArray(32), ByteArray(32), 1, 1)
        assertFailsWith<IllegalArgumentException> {
            s.bob.outbox.enqueue(ContentEnvelope.Photo("p", NOW, att, 1, 1, c = "x".repeat(4097)), "acc-ana")
        }
        s.bob.outbox.enqueue(ContentEnvelope.Photo("p", NOW, att, 1, 1, c = "x".repeat(4096)), "acc-ana")
        assertEquals(4096, s.bob.repo.message("p")!!.caption!!.length)
    }

    // Sweep

    @Test fun sweepExpiredRemovesRowsAndFilesAndReturnsIds() = runTest {
        val s = setup()
        val st = store(s.bob)
        val blob = File.createTempFile("att", ".enc").apply { writeText("x") }
        s.deliver(ContentEnvelope.Text("a", NOW, "findable", ttl = 100))
        s.deliver(ContentEnvelope.Photo("b", NOW, Att("att-b", ByteArray(32), ByteArray(32), 1, 1), 1, 1, ttl = 100))
        s.bob.repo.setAttachmentState("att-b", "local", 1, blob.path)
        s.deliver(ContentEnvelope.Text("keep", NOW, "kept"))
        assertEquals(emptyList(), st.sweepExpired(NOW + 99_999))
        assertEquals(setOf("a", "b"), st.sweepExpired(NOW + 100_000).toSet())
        assertFalse(blob.exists())
        assertEquals(listOf("keep"), s.chatMessages().map { it.id })
        assertEquals(listOf("keep"), st.chat("acc-ana")!!.messages.map { it.id }, "the cache is reloaded")
        assertTrue(st.searchMessages("findable", 5).isEmpty())
    }

    // Profile through the store

    @Test fun chatStoreSavesAndReadsProfiles() = runTest {
        val s = setup()
        val st = store(s.bob)
        assertNull(st.profile("acc-ana"))
        assertTrue(st.saveProfile("acc-ana", "Ann", JPEG, 4))
        assertFalse(st.saveProfile("acc-ana", "Old", null, 4))
        assertEquals("Ann", st.profile("acc-ana")!!.name)
        assertEquals(4, st.profile("acc-ana")!!.ver)
    }
}
