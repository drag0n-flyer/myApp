package org.example.app.messaging.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.example.app.core.chats.PollOption

class PollEnvelopeTest {
    // Compact, sorted keys: the exact bytes the other platform writes.
    private val poll = """{"at":1700000000000,"id":"p1","k":"poll","multi":true,"options":[{"id":"o1","text":"Pizza"},{"id":"o2","text":"Sushi"}],"question":"Lunch?","v":1}"""
    private val vote = """{"at":1700000000000,"id":"v1","k":"pollVote","optionIDs":["o1","o2"],"pollID":"p1","v":1}"""
    private val retract = """{"at":1700000000000,"id":"v2","k":"pollVote","optionIDs":[],"pollID":"p1","v":1}"""

    private fun decode(s: String) = ContentCodec.decode(s.encodeToByteArray())

    @Test fun pollRoundTripsByteForByte() {
        val env = assertIs<ContentEnvelope.Poll>(decode(poll))
        assertEquals("Lunch?", env.question)
        assertEquals(listOf(PollOption("o1", "Pizza"), PollOption("o2", "Sushi")), env.options)
        assertEquals(true, env.multi)
        assertEquals(poll, ContentCodec.encode(env).decodeToString())
    }

    @Test fun pollVoteRoundTripsByteForByte() {
        val env = assertIs<ContentEnvelope.PollVote>(decode(vote))
        assertEquals("p1", env.pollId)
        assertEquals(listOf("o1", "o2"), env.optionIds)
        assertEquals(vote, ContentCodec.encode(env).decodeToString())
        assertEquals(emptyList(), assertIs<ContentEnvelope.PollVote>(decode(retract)).optionIds)
        assertEquals(retract, ContentCodec.encode(decode(retract)).decodeToString())
    }

    private fun invalidPoll(question: String = "Q", options: String = """[{"id":"a","text":"x"},{"id":"b","text":"y"}]""", multi: String = "false") =
        """{"at":1,"id":"p","k":"poll","multi":$multi,"options":$options,"question":"$question","v":1}"""

    @Test fun validationFailuresDegradeToUnsupported() {
        for (bad in listOf(
            invalidPoll(question = ""),
            invalidPoll(question = "q".repeat(301)),
            invalidPoll(options = """[{"id":"a","text":"x"}]"""),
            invalidPoll(options = (1..13).joinToString(",", "[", "]") { """{"id":"o$it","text":"x"}""" }),
            invalidPoll(options = """[{"id":"a","text":"x"},{"id":"a","text":"y"}]"""),
            invalidPoll(options = """[{"id":"","text":"x"},{"id":"b","text":"y"}]"""),
            invalidPoll(options = """[{"id":"123456789","text":"x"},{"id":"b","text":"y"}]"""),
            invalidPoll(options = """[{"id":"a","text":""},{"id":"b","text":"y"}]"""),
            invalidPoll(options = """[{"id":"a","text":"${"x".repeat(101)}"},{"id":"b","text":"y"}]"""),
            invalidPoll(multi = "\"yes\""),
            """{"at":1,"id":"p","k":"poll","options":[{"id":"a","text":"x"},{"id":"b","text":"y"}],"question":"Q","v":1}""",
            """{"at":1,"id":"v","k":"pollVote","optionIDs":"o1","pollID":"p1","v":1}""",
            """{"at":1,"id":"v","k":"pollVote","optionIDs":[1],"pollID":"p1","v":1}""",
            """{"at":1,"id":"v","k":"pollVote","optionIDs":["o1"],"v":1}""",
        )) {
            assertIs<ContentEnvelope.Unknown>(decode(bad), bad)
        }
    }

    @Test fun aPollOfALimitSizeIsKept() {
        val twelve = (1..12).joinToString(",", "[", "]") { """{"id":"o$it","text":"x"}""" }
        val env = decode(invalidPoll(question = "q".repeat(300), options = twelve))
        assertIs<ContentEnvelope.Poll>(env)
    }

    @Test fun anUnknownKindStillDegrades() {
        assertEquals("pollResult", assertIs<ContentEnvelope.Unknown>(decode("""{"at":1,"id":"x","k":"pollResult","v":1}""")).kind)
    }
}
