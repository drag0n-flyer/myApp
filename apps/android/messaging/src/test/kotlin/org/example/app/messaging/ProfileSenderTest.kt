package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.send.ProfileSender
import org.example.app.messaging.testing.InMemoryRepository

private val PHOTO = byteArrayOf(-1, -40, 7, 7)

/** Who gets a `profile` envelope and when: every one-to-one chat, once per version (task P5). */
class ProfileSenderTest {
    private val repo = InMemoryRepository()
    private val sent = mutableListOf<Triple<String, String, Int>>()
    private val photos = mutableListOf<ByteArray?>()
    private var name = "Me"
    private val sender = ProfileSender(repo, "acc-me", { name }) { chat, n, photo, ver ->
        sent += Triple(chat, n, ver); photos += photo
    }

    private fun chat(id: String, request: Boolean = false, conversation: Boolean = true) {
        repo.upsertChat(ChatRecord(id, isRequest = request, unread = 0, muted = false, lastActivity = 1))
        if (conversation) repo.upsertConversation(ConversationRecord("srv-$id", id, id.encodeToByteArray(), "e2ee", null, false, 1, true, 1))
    }

    @Test fun threeChatsGetThreeSends() {
        chat("a"); chat("b"); chat("c")
        sender.announce()
        assertEquals(setOf("a", "b", "c"), sent.map { it.first }.toSet())
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.second == "Me" && it.third == 1 })
    }

    @Test fun noChangeSendsNothing() {
        chat("a"); chat("b")
        sender.announce()
        sent.clear()
        sender.announce()
        sender.sync()
        assertEquals(emptyList(), sent)
    }

    @Test fun aNameChangeBumpsTheVersionAndSendsToEveryChatAgain() {
        chat("a"); chat("b")
        sender.announce()
        sent.clear()
        name = "Mia"
        sender.announce()
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.second == "Mia" && it.third == 2 })
        assertEquals(2, sender.ver())
    }

    @Test fun aPhotoChangeAndItsRemovalEachBumpTheVersion() {
        chat("a")
        sender.announce(); sent.clear(); photos.clear()
        sender.setPhoto(PHOTO)
        assertEquals(listOf(Triple("a", "Me", 2)), sent)
        assertContentEquals(PHOTO, photos.single())
        assertContentEquals(PHOTO, sender.photo())
        sent.clear(); photos.clear()
        sender.setPhoto(null)
        assertEquals(listOf(Triple("a", "Me", 3)), sent)
        assertEquals(null, photos.single())
        assertEquals(null, sender.photo())
    }

    @Test fun aBlockedContactGetsNothingUntilUnblocked() {
        chat("a"); chat("b")
        repo.block("b", 1)
        sender.announce()
        assertEquals(listOf("a"), sent.map { it.first })
        sent.clear()
        repo.unblock("b")
        sender.sync()
        assertEquals(listOf("b"), sent.map { it.first })
    }

    @Test fun requestsAndChatsWithoutAConversationAreSkippedThenSentOnceTheyExist() {
        chat("req", request = true); chat("new", conversation = false); chat("ok")
        sender.announce()
        assertEquals(listOf("ok"), sent.map { it.first })
        sent.clear()
        repo.upsertConversation(ConversationRecord("srv-new", "new", "new".encodeToByteArray(), "e2ee", null, false, 1, true, 1))
        repo.upsertChat(ChatRecord("req", isRequest = false, unread = 0, muted = false, lastActivity = 1))
        sender.sync()
        assertEquals(setOf("new", "req"), sent.map { it.first }.toSet())
        assertTrue(sent.all { it.third == 1 })
    }

    @Test fun aNewChatGetsTheCurrentVersionOnce() {
        chat("a")
        sender.announce()
        chat("late")
        sent.clear()
        sender.sync(); sender.sync()
        assertEquals(listOf(Triple("late", "Me", 1)), sent)
    }

    @Test fun noNameMeansNothingToSend() {
        name = ""
        chat("a")
        sender.announce(); sender.sync()
        assertEquals(emptyList(), sent)
        assertEquals(0, sender.ver())
    }

    @Test fun syncSeedsTheFirstVersionWhenANameIsKnown() {
        chat("a")
        sender.sync()
        assertEquals(listOf(Triple("a", "Me", 1)), sent)
    }

    @Test fun myOwnChatIsNeverASendTarget() {
        chat("acc-me"); chat("a")
        sender.announce()
        assertEquals(listOf("a"), sent.map { it.first })
    }
}
