package org.example.app.core.chats

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.app.core.chats.MessageAction.Copy
import org.example.app.core.chats.MessageAction.Delete
import org.example.app.core.chats.MessageAction.Edit
import org.example.app.core.chats.MessageAction.Forward
import org.example.app.core.chats.MessageAction.React
import org.example.app.core.chats.MessageAction.Reply

class MessageActionsTest {
    private fun m(content: MessageContent, status: DeliveryStatus = DeliveryStatus.Sent) =
        Message("x", "a", Instant.EPOCH, content, status, null, emptyList())

    private val text = MessageContent.Text("hi")
    private val photo = MessageContent.Photo(1000)

    @Test fun aCaptionedPhotoCanBeCopiedAndItsSenderCanEditIt() {
        val captioned = MessageContent.Photo(1000, "words")
        assertEquals(listOf(Reply, React, Copy, Edit, Delete), MessageActions.available(m(captioned), isMine = true))
        assertEquals(listOf(Reply, React, Copy), MessageActions.available(m(captioned), isMine = false))
        val file = MessageContent.File("a.pdf", 5, "application/pdf", "words")
        assertEquals(listOf(Reply, React, Copy, Edit, Delete), MessageActions.available(m(file), isMine = true))
    }

    @Test fun r1APhotoWithoutACaptionOffersEditToItsSenderToAddOneButNoCopy() {
        assertEquals(listOf(Reply, React, Edit, Delete), MessageActions.available(m(photo), isMine = true))
        assertEquals(listOf(Reply, React), MessageActions.available(m(photo), isMine = false))
        val file = MessageContent.File("a.pdf", 5, "application/pdf")
        assertEquals(listOf(Reply, React, Edit, Delete), MessageActions.available(m(file), isMine = true))
    }

    @Test fun r1AnEmptyCaptionIsNoCaption() {
        val empty = MessageContent.Photo(1000, "")
        assertEquals(listOf(Reply, React, Edit, Delete), MessageActions.available(m(empty), isMine = true))
        assertEquals(listOf(Reply, React), MessageActions.available(m(empty), isMine = false))
    }

    @Test fun aVoiceMessageIsNeverEditable() =
        assertEquals(listOf(Reply, React, Delete), MessageActions.available(m(MessageContent.Voice(3.0)), isMine = true))

    @Test fun ownTextHasEverything() =
        assertEquals(listOf(Reply, React, Copy, Forward, Edit, Delete), MessageActions.available(m(text), isMine = true))

    @Test fun othersTextCannotBeEditedOrDeleted() =
        assertEquals(listOf(Reply, React, Copy, Forward), MessageActions.available(m(text), isMine = false))


    @Test fun othersPhoto() = assertEquals(listOf(Reply, React), MessageActions.available(m(photo), isMine = false))

    @Test fun nothingForDeletedSystemCallAndUnsupported() {
        val none = listOf(
            MessageContent.Deleted, MessageContent.Unsupported, MessageContent.Call(CallSummary.MissedAudio),
            MessageContent.System(SystemEvent.SafetyNumberChanged),
        )
        for (content in none) for (mine in listOf(true, false)) {
            assertEquals(emptyList(), MessageActions.available(m(content), mine), "$content mine=$mine")
        }
    }

    @Test fun failedOwnTextCanOnlyBeCopiedOrDeleted() =
        assertEquals(listOf(Copy, Delete), MessageActions.available(m(text, DeliveryStatus.Failed), isMine = true))

    @Test fun sendingOwnTextStillEditable() =
        assertTrue(Edit in MessageActions.available(m(text, DeliveryStatus.Sending), isMine = true))

    // Review focus 3: the message changed or vanished while the menu was open.
    @Test fun anActionOnAVanishedMessageIsDropped() = assertFalse(MessageActions.stillApplies(Delete, null, isMine = true))

    @Test fun anActionOnAMessageThatWasDeletedMeanwhileIsDropped() {
        val deleted = m(MessageContent.Deleted)
        for (action in MessageAction.entries) assertFalse(MessageActions.stillApplies(action, deleted, isMine = true), "$action")
    }

    @Test fun anEditOfAMessageThatFailedMeanwhileIsDropped() {
        assertFalse(MessageActions.stillApplies(Edit, m(text, DeliveryStatus.Failed), isMine = true))
        assertTrue(MessageActions.stillApplies(Edit, m(text), isMine = true))
    }
}
