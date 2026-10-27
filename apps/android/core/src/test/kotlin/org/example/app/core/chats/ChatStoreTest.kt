package org.example.app.core.chats

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatStoreTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private fun store() = SampleChatStore(SampleData.make(now), clock = { now })
    private val stranger2 = Contact("stranger2", "Someone New", "stranger2")

    @Test fun sampleDataMatchesIPhoneContent() {
        val data = SampleData.make(now)
        assertEquals(10, data.chats.size)
        assertEquals(listOf("All", "Unread", "Personal", "Work"), data.folders.map { it.name })
        val names = data.contacts.map { it.displayName }
        assertTrue("مريم" in names)
        assertTrue("प्रिया" in names)
        assertTrue(names.any { it.length >= 30 })
        assertEquals("Dr. Anantharaman Venkatasubramanian", names.first { it.length >= 30 })
        assertEquals(setOf("neha"), data.directory.keys)
        // Direct chats only: one chat per contact, one counterpart each.
        assertEquals(data.chats.size, data.chats.map { it.contactId }.toSet().size)
        assertEquals(1, data.chats.count { it.isRequest })
        assertEquals(ChatEncryption.ServerReadable, data.chats.first { it.id == "chat-sam" }.encryption)
        assertEquals(2, data.chats.first { it.id == "chat-riya" }.unreadCount)
        assertEquals(6, data.chats.first { it.id == "chat-riya" }.messages.size)
    }

    @Test fun allFolderIsNewestFirstAndExcludesRequests() {
        val s = store()
        val all = s.chats(ChatFolder.All)
        assertEquals("chat-riya", all.first().id)
        assertEquals(9, all.size)
        assertTrue(all.none { it.isRequest })
        assertEquals(listOf("chat-riya"), s.chats(ChatFolder.Unread).map { it.id })
        assertEquals(1, s.unreadChatCount())
    }

    @Test fun strangersFirstMessageLandsInRequestsNotFoldersOrUnread() {
        val s = store()
        s.receive(MessageContent.Text("hello"), stranger2)
        val request = s.requests.value.first { it.contactId == "stranger2" }
        assertTrue(request.isRequest)
        assertEquals(0, request.unreadCount)
        assertTrue(request.folderIds.isEmpty())
        assertTrue(s.chats(ChatFolder.All).none { it.contactId == "stranger2" })
        assertTrue(s.chats(ChatFolder.Unread).none { it.contactId == "stranger2" })
        assertEquals(1, s.unreadChatCount())
        assertEquals(2, s.requests.value.size)
        assertNull(s.contacts.value.firstOrNull { it.id == "stranger2" })
        assertEquals("Someone New", s.contact("stranger2")?.displayName)
    }

    @Test fun acceptingARequestMovesItToChatsAndAddsTheContact() {
        val s = store()
        s.accept("chat-stranger1")
        assertTrue(s.requests.value.isEmpty())
        assertNotNull(s.chats(ChatFolder.All).firstOrNull { it.id == "chat-stranger1" })
        assertNotNull(s.contacts.value.firstOrNull { it.id == "stranger1" })
    }

    @Test fun blockingRemovesChatAndContact() {
        val s = store()
        s.block("riya")
        assertTrue("riya" in s.blockedIds.value)
        assertNull(s.chat("chat-riya"))
        assertNull(s.contacts.value.firstOrNull { it.id == "riya" })
        s.block("stranger1")
        assertTrue(s.requests.value.isEmpty())
    }

    @Test fun reportRemembersChat() {
        val s = store()
        s.report("chat-stranger1")
        assertTrue("chat-stranger1" in s.reportedChatIds.value)
    }

    @Test fun markReadClearsUnread() {
        val s = store()
        s.markRead("chat-riya")
        assertEquals(0, s.chat("chat-riya")!!.unreadCount)
        assertTrue(s.chats(ChatFolder.Unread).isEmpty())
    }

    @Test fun receiveFromContactCountsUnreadAndSendAppends() {
        val s = store()
        val arjun = s.contact("arjun")!!
        s.receive(MessageContent.Text("hey"), arjun)
        assertEquals(1, s.chat("chat-arjun")!!.unreadCount)
        s.send(MessageContent.Text("hi"), "chat-arjun", replyTo = null)
        val last = s.chat("chat-arjun")!!.lastMessage!!
        assertEquals("me", last.senderId)
        assertEquals(DeliveryStatus.Sent, last.status)
    }

    @Test fun incomingCallFromContactRingsAndStrangerIsSilentMissed() {
        val s = store()
        assertEquals(IncomingCallDecision.Ring, s.incomingCall("riya", video = false))
        assertEquals(IncomingCallDecision.SilentMissed, s.incomingCall("stranger1", video = true))
        val missed = s.chat("chat-stranger1")!!.lastMessage!!
        assertEquals(MessageContent.Call(CallSummary.MissedVideo), missed.content)
        assertEquals(0, s.chat("chat-stranger1")!!.unreadCount)
        assertEquals(IncomingCallDecision.SilentMissed, s.incomingCall("never-seen", video = false))
        assertTrue(s.requests.value.any { it.contactId == "never-seen" })
    }

    @Test fun recordCallAddsCallMessage() {
        val s = store()
        s.recordCall(CallSummary.Audio(125.0), "kiran", outgoing = true, at = now)
        val m = s.chat("chat-kiran")!!.lastMessage!!
        assertEquals("me", m.senderId)
        assertEquals(MessageContent.Call(CallSummary.Audio(125.0)), m.content)
        s.recordCall(CallSummary.MissedAudio, "newcomer-none", outgoing = false, at = now)
    }

    @Test fun pagingAndSearch() {
        val s = store()
        val page = s.messages("chat-riya", before = null, limit = 2)
        assertEquals(2, page.size)
        assertEquals("s6", page.last().id)
        val older = s.messages("chat-riya", before = page.first().id, limit = 10)
        assertEquals(listOf("s1", "s2", "s3", "s4"), older.map { it.id })
        val hits = s.searchMessages("REPORT", limit = 10)
        assertTrue(hits.isNotEmpty() && hits.all { it.chatId == "chat-sam" })
        assertTrue(s.searchMessages("  ", 10).isEmpty())
    }

    @Test fun editReactDelete() {
        val s = store()
        s.send(MessageContent.Text("typo"), "chat-kiran", null)
        val id = s.chat("chat-kiran")!!.lastMessage!!.id
        s.edit(id, "chat-kiran", "fixed")
        assertEquals(MessageContent.Text("fixed"), s.chat("chat-kiran")!!.lastMessage!!.content)
        assertNotNull(s.chat("chat-kiran")!!.lastMessage!!.editedAt)
        s.react("👍", id, "chat-kiran"); s.react("👍", id, "chat-kiran")
        assertTrue(s.chat("chat-kiran")!!.lastMessage!!.reactions.isEmpty())
        s.delete(id, "chat-kiran")
        assertEquals(MessageContent.Deleted, s.chat("chat-kiran")!!.lastMessage!!.content)
    }

    @Test fun foldersAddRenameMoveDelete() {
        val s = store()
        val f = s.addFolder("Family", setOf("chat-kiran"))
        assertEquals(listOf("chat-kiran"), s.chats(f).map { it.id })
        s.renameFolder(f.id, "Fam")
        assertEquals("Fam", s.folders.value.first { it.id == f.id }.name)
        s.moveFolder(f.id, 0)
        assertEquals(f.id, s.folders.value[2].id)
        s.renameFolder("all", "x")
        assertEquals("All", s.folders.value.first().name)
        s.deleteFolder(f.id)
        assertTrue(s.folders.value.none { it.id == f.id })
        assertTrue(s.chat("chat-kiran")!!.folderIds.isEmpty())
    }

    @Test fun addContactFindsDirectoryNames() = runTest {
        val s = store()
        assertEquals("Neha", s.addContact("Neha")?.displayName)
        assertNull(s.addContact("nobody"))
        assertEquals("riya", s.addContact("riya")?.id)
    }

    @Test fun ensureChatCreatesEmptyChat() = runTest {
        val s = store()
        s.addContact("neha")
        val id = s.ensureChat("neha")
        assertEquals(id, s.ensureChat("neha"))
        assertTrue(s.chat(id)!!.messages.isEmpty())
        assertFalse(s.chat(id)!!.isRequest)
    }

    @Test fun safetyNumberIsStableAndVerifiable() {
        val s = store()
        val n = s.safetyNumber("chat-riya")!!
        assertEquals(12, n.groups.size)
        assertTrue(n.groups.all { it.length == 5 && it.all(Char::isDigit) })
        assertEquals(n.groups, s.safetyNumber("chat-riya")!!.groups)
        s.setVerified(true, "chat-riya")
        assertTrue(s.safetyNumber("chat-riya")!!.isVerified)
        assertNull(s.safetyNumber("nope"))
    }

    @Test fun messagePreviewKeys() {
        fun m(c: MessageContent) = Message("x", "a", now, c, DeliveryStatus.Read, null, emptyList())
        assertEquals(MessagePreview.Text("hi"), m(MessageContent.Text("hi")).preview)
        assertEquals(MessagePreview.Resource("preview_voice", listOf("0:14")), m(MessageContent.Voice(14.0)).preview)
        assertEquals(MessagePreview.Resource("preview_video_call", listOf("12")), m(MessageContent.Call(CallSummary.Video(720.0))).preview)
        assertEquals(MessagePreview.Resource("preview_missed_audio_call"), m(MessageContent.Call(CallSummary.MissedAudio)).preview)
    }
}
