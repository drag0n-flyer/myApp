package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.PollOption
import org.example.app.messaging.ChatMapping
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.PollVoteRecord

/** Polls and votes through the ordinary outbox and inbox of a three-member group. */
class PollMessagesTest {
    private val chatId = "g:conv-0001"
    private val options = listOf(PollOption("a", "Pizza"), PollOption("b", "Sushi"), PollOption("c", "Tacos"))

    private suspend fun joined(): GroupWorld {
        val w = GroupWorld.trio()
        val ana = w.device("ana1")
        ana.groups.create(listOf("acc-bob", "acc-cat"), "Book club", null)
        ana.outbox.flush()
        for (id in listOf("bob1", "cat1")) w.device(id).processor.sync()
        ana.processor.sync()
        return w
    }

    private suspend fun GroupWorld.poll(from: String, id: String, multi: Boolean = false, at: Long = NOW + 10) {
        val d = device(from)
        d.outbox.enqueue(ContentEnvelope.Poll(id, at, "Lunch?", options, multi), chatId)
        d.outbox.flush()
    }

    private suspend fun GroupWorld.vote(from: String, pollId: String, ids: List<String>, at: Long) {
        val d = device(from)
        d.outbox.enqueueControl(chatId) { id, _ -> ContentEnvelope.PollVote(id, at, pollId, ids) }
        d.outbox.flush()
    }

    private fun GroupWorld.shown(on: String, pollId: String, me: String): MessageContent.Poll {
        val d = device(on)
        val record = assertNotNull(d.repo.message(pollId))
        val m = ChatMapping.message(record, null, emptyList(), showRead = false, votes = { d.repo.pollVotes(it) }, me = me, displayName = { null })
        return assertIs<MessageContent.Poll>(m.content)
    }

    @Test fun aPollIsStoredAsAMessageForEveryMember() = runTest {
        val w = joined()
        w.poll("ana1", "p-1")
        for (id in listOf("bob1", "cat1")) {
            w.device(id).processor.sync()
            val c = w.shown(id, "p-1", "acc-${id.dropLast(1)}")
            assertEquals("Lunch?", c.question)
            assertEquals(options, c.options)
            assertEquals(mapOf("a" to 0, "b" to 0, "c" to 0), c.tally.counts)
        }
        assertEquals("acc-ana", w.device("bob1").repo.message("p-1")!!.senderAccount)
    }

    @Test fun votesAreCountedAndTheLatestOneReplacesTheEarlier() = runTest {
        val w = joined()
        w.poll("ana1", "p-1", multi = true)
        w.vote("bob1", "p-1", listOf("a"), NOW + 20)
        w.vote("bob1", "p-1", listOf("b", "c"), NOW + 21)
        w.vote("cat1", "p-1", listOf("b"), NOW + 22)
        // An older vote arriving after a newer one changes nothing.
        w.vote("cat1", "p-1", listOf("a"), NOW + 15)
        val ana = w.device("ana1")
        ana.processor.sync()
        val t = w.shown("ana1", "p-1", "acc-ana").tally
        assertEquals(mapOf("a" to 0, "b" to 2, "c" to 1), t.counts)
        assertEquals(2, t.voterCount)
        assertEquals(setOf("acc-bob", "acc-cat"), t.voters.getValue("b").toSet())
        w.vote("bob1", "p-1", emptyList(), NOW + 30)
        ana.processor.sync()
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 0), w.shown("ana1", "p-1", "acc-ana").tally.counts)
    }

    @Test fun aVoteForAnUnknownPollWaitsForIt() = runTest {
        val w = joined()
        w.vote("bob1", "p-9", listOf("b"), NOW + 20)
        val ana = w.device("ana1")
        ana.processor.sync()
        assertEquals(listOf(PollVoteRecord("p-9", "acc-bob", chatId, listOf("b"), NOW + 20)), ana.repo.pollVotes("p-9"))
        assertEquals(null, ana.repo.message("p-9"), "a vote is never a message")
        w.poll("cat1", "p-9", at = NOW + 25)
        ana.processor.sync()
        val t = w.shown("ana1", "p-9", "acc-ana").tally
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 0), t.counts)
    }

    @Test fun invalidOptionIdsAreIgnoredAndSingleChoiceKeepsTheFirst() = runTest {
        val w = joined()
        w.poll("ana1", "p-1", multi = false)
        w.vote("bob1", "p-1", listOf("zzz", "c", "a"), NOW + 20)
        w.vote("cat1", "p-1", listOf("zzz"), NOW + 21)
        val ana = w.device("ana1")
        ana.processor.sync()
        val t = w.shown("ana1", "p-1", "acc-ana").tally
        assertEquals(mapOf("a" to 0, "b" to 0, "c" to 1), t.counts)
        assertEquals(1, t.voterCount)
    }

    @Test fun myOwnVoteFromAnotherDeviceIsMine() = runTest {
        val w = joined()
        w.poll("ana1", "p-1")
        w.vote("bob1", "p-1", listOf("b"), NOW + 20)
        w.device("cat1").processor.sync()
        assertEquals(setOf("b"), w.shown("cat1", "p-1", "acc-bob").tally.mine)
        assertEquals(emptySet(), w.shown("cat1", "p-1", "acc-cat").tally.mine)
    }
}
