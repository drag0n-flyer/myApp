package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.NotificationOutcome
import org.example.app.messaging.content.ContentEnvelope
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

private val JPEG = TestJpeg.of()

/** The store shows a contact's current profile everywhere and sends the person's own (task P5). */
class ProfileStoreTest {
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

    private suspend fun setup(): Pair<Rig, org.example.app.messaging.ports.ConversationRecord> {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return rig to conv
    }

    @Test fun aContactsProfileNameAndPhotoShowEverywhereTheContactDoes() = runTest {
        val (rig, conv) = setup()
        val st = store(rig.bob1)
        assertEquals("Ana", st.contact("acc-ana")!!.displayName, "the lookup name until a profile arrives")
        rig.ana1.send(conv, ContentEnvelope.Profile("p1", NOW, "Ann", JPEG, 1))
        st.syncInbox(); st.settle()
        assertEquals("Ann", st.contact("acc-ana")!!.displayName)
        assertEquals("Ann", st.contacts.value.single().displayName)
        assertContentEquals(JPEG, st.profiles.value["acc-ana"]!!.photo)
    }

    @Test fun myPhotoIsKeptAndSentToTheChatWithMyNameAndVersion() = runTest {
        val (rig, _) = setup()
        val st = store(rig.bob1)
        st.setMyDisplayName("Bob")
        st.setMyPhoto(JPEG); st.settle()
        assertContentEquals(JPEG, st.profiles.value["acc-bob"]!!.photo)
        rig.ana1.processor.sync()
        val got = rig.ana1.repo.profile("acc-bob")!!
        assertEquals("Bob", got.name); assertContentEquals(JPEG, got.photo); assertTrue(got.ver >= 1_700_000_000, "a version is at least the Unix time")
        assertNull(rig.ana1.repo.message("none"))
    }

    @Test fun removingMyPhotoSendsANewerProfileWithoutOne() = runTest {
        val (rig, _) = setup()
        val st = store(rig.bob1)
        st.setMyDisplayName("Bob")
        st.setMyPhoto(JPEG); st.settle()
        val first = rig.bob1.repo.get("profile.ver")!!.decodeToString().toInt()
        st.setMyPhoto(null); st.settle()
        rig.ana1.processor.sync()
        val got = rig.ana1.repo.profile("acc-bob")!!
        assertNull(got.photo); assertTrue(got.ver > first)
        assertNull(st.profiles.value["acc-bob"]?.photo)
    }

    @Test fun anAnnouncedNameChangeIsSentOnce() = runTest {
        val (rig, _) = setup()
        val st = store(rig.bob1)
        st.setMyDisplayName("Bob")
        st.announceProfile(); st.settle()
        st.announceProfile(); st.settle()
        rig.ana1.processor.sync()
        val first = rig.ana1.repo.profile("acc-bob")!!.ver
        assertTrue(first >= 1_700_000_000)
        st.setMyDisplayName("Robert")
        st.announceProfile(); st.settle()
        rig.ana1.processor.sync()
        assertEquals("Robert", rig.ana1.repo.profile("acc-bob")!!.name)
        assertTrue(rig.ana1.repo.profile("acc-bob")!!.ver > first)
    }

    // Ruling B

    @Test fun aProfileFromARequestIsStoredButNotShownUntilAccepted() = runTest {
        val (rig, conv) = setup()
        rig.bob1.repo.upsertChat(rig.bob1.repo.chat("acc-ana")!!.copy(isRequest = true))
        val st = store(rig.bob1)
        rig.ana1.send(conv, ContentEnvelope.Profile("p1", NOW, "Ann", JPEG, 1))
        st.syncInbox(); st.settle()
        assertEquals("Ann", rig.bob1.repo.profile("acc-ana")!!.name, "stored")
        assertEquals("Ana", st.contact("acc-ana")!!.displayName, "the lookup name shows")
        assertNull(st.profiles.value["acc-ana"], "no photo shows")
        st.accept("acc-ana"); st.settle()
        assertEquals("Ann", st.contact("acc-ana")!!.displayName)
        assertContentEquals(JPEG, st.profiles.value["acc-ana"]!!.photo)
    }

    // Ruling C: the notification title is the name the chat list shows.

    @Test fun theNotificationTitleUsesTheSameNameAsTheChatList() = runTest {
        val (rig, conv) = setup()
        val np = rig.bob1.notifications()
        suspend fun push(env: ContentEnvelope): NotificationOutcome {
            rig.ana1.send(conv, env)
            val item = rig.server.pending("acc-bob/bob1").last()
            return rig.bob1.lock.withLock { np.onMessagePush(org.example.app.messaging.pad(item.payload)) }
        }
        assertEquals("Ana", (push(ContentEnvelope.Text("n1", NOW, "hi")) as NotificationOutcome.Message).senderName)
        rig.bob1.repo.saveProfile("acc-ana", "Ann", null, 2)
        assertEquals("Ann", (push(ContentEnvelope.Text("n2", NOW + 1, "hi")) as NotificationOutcome.Message).senderName)
        rig.bob1.repo.block("acc-ana", NOW)
        assertIs<NotificationOutcome.Nothing>(push(ContentEnvelope.Text("n3", NOW + 2, "hi")))
    }

    // Review fix: a photo change announces the name the repo has now, not one cached before another device's change was adopted.

    @Test fun aPhotoChangeSendsAJustAdoptedName() = runTest {
        val (rig, conv) = setup()
        rig.bob2.processor.sync()
        rig.bob1.repo.upsertContact(ContactRecord("acc-bob", "bob", "Bob", false, NOW))
        val st = store(rig.bob1) // its cache holds "Bob"
        rig.bob2.send(conv, ContentEnvelope.Profile("o1", NOW, "Bobby", null, 1_900_000_000))
        rig.bob1.processor.sync() // adopted straight into the repo; the store has not reloaded
        st.setMyPhoto(JPEG); st.settle()
        rig.ana1.processor.sync()
        assertEquals("Bobby", rig.ana1.repo.profile("acc-bob")!!.name)
    }
}
