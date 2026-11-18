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
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

private val JPEG = byteArrayOf(-1, -40, 1, 2, 3)

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
        assertEquals("Bob", got.name); assertContentEquals(JPEG, got.photo); assertEquals(1, got.ver)
        assertNull(rig.ana1.repo.message("none"))
    }

    @Test fun removingMyPhotoSendsANewerProfileWithoutOne() = runTest {
        val (rig, _) = setup()
        val st = store(rig.bob1)
        st.setMyDisplayName("Bob")
        st.setMyPhoto(JPEG); st.settle()
        st.setMyPhoto(null); st.settle()
        rig.ana1.processor.sync()
        val got = rig.ana1.repo.profile("acc-bob")!!
        assertNull(got.photo); assertEquals(2, got.ver)
        assertNull(st.profiles.value["acc-bob"]?.photo)
    }

    @Test fun anAnnouncedNameChangeIsSentOnce() = runTest {
        val (rig, _) = setup()
        val st = store(rig.bob1)
        st.setMyDisplayName("Bob")
        st.announceProfile(); st.settle()
        st.announceProfile(); st.settle()
        rig.ana1.processor.sync()
        assertEquals(1, rig.ana1.repo.profile("acc-bob")!!.ver)
        st.setMyDisplayName("Robert")
        st.announceProfile(); st.settle()
        rig.ana1.processor.sync()
        assertEquals("Robert", rig.ana1.repo.profile("acc-bob")!!.name)
        assertEquals(2, rig.ana1.repo.profile("acc-bob")!!.ver)
    }
}
