package org.example.app.core.chats

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.core.settings.MediaKind

class SendPlanTest {
    private fun photo(n: Int) = Picked("p$n", MediaKind.Photo, 10)

    @Test fun threePhotosAndACaptionSendThreeItemsAndTheCaptionRidesOnTheFirst() {
        val items = listOf(photo(1), photo(2), photo(3))
        val result = SendPlan.plan(items, " hi ", 1_000)
        assertEquals(
            SendPlan.Result.Ok(listOf(SendPlan.Send.Attachment(items[0], "hi"), SendPlan.Send.Attachment(items[1]), SendPlan.Send.Attachment(items[2]))),
            result,
        )
    }

    @Test fun noTextMessageIsEverPlanned() {
        val sends = (SendPlan.plan(listOf(photo(1), photo(2)), "caption", 1_000) as SendPlan.Result.Ok).sends
        assertEquals(2, sends.size)
        assertEquals(listOf<String?>("caption", null), sends.map { it.caption })
    }

    @Test fun aBlankCaptionIsNoCaption() {
        val result = SendPlan.plan(listOf(photo(1)), "  \n ", 1_000) as SendPlan.Result.Ok
        assertEquals(listOf(SendPlan.Send.Attachment(photo(1), null)), result.sends)
    }

    @Test fun aCaptionIsTrimmedAndKeptWhole() {
        val result = SendPlan.plan(listOf(photo(1)), "  line one\nline two  ", 1_000) as SendPlan.Result.Ok
        assertEquals("line one\nline two", result.sends.single().caption)
    }

    @Test fun elevenItemsAreTooMany() = assertEquals(SendPlan.Result.Refused(SendPlan.Refusal.TooMany), SendPlan.plan((1..11).map(::photo), "", 1_000))

    @Test fun tenItemsAreFine() = assertEquals(10, (SendPlan.plan((1..10).map(::photo), "", 1_000) as SendPlan.Result.Ok).sends.size)

    @Test fun aVideoOneByteOverTheLimitIsRefused() {
        assertEquals(SendPlan.Result.Refused(SendPlan.Refusal.VideoTooBig), SendPlan.plan(listOf(Picked("v", MediaKind.Video, 1_001)), "", 1_000))
        assertEquals(SendPlan.Result.Ok(listOf(SendPlan.Send.Attachment(Picked("v", MediaKind.Video, 1_000)))), SendPlan.plan(listOf(Picked("v", MediaKind.Video, 1_000)), "", 1_000))
    }

    @Test fun anEmptyPickSendsNothing() = assertEquals(SendPlan.Result.Ok(emptyList()), SendPlan.plan(emptyList(), "caption", 1_000))
}

class GalleryGroupsTest {
    private fun message(id: String, at: String) =
        Message(id, "me", Instant.parse(at), MessageContent.Photo(1), DeliveryStatus.Sent, null, emptyList())

    @Test fun groupsNewestFirstAcrossAYearBoundary() {
        val items = listOf(
            message("a", "2025-12-31T12:00:00Z"), message("b", "2026-01-01T12:00:00Z"), message("c", "2026-01-20T12:00:00Z"),
        )
        val groups = GalleryGroups.byMonth(items, ZoneId.of("UTC"))
        assertEquals(listOf(YearMonth.of(2026, 1), YearMonth.of(2025, 12)), groups.map { it.month })
        assertEquals(listOf("c", "b"), groups[0].items.map { it.id })
        assertEquals(listOf("a"), groups[1].items.map { it.id })
    }

    @Test fun theTimeZoneDecidesTheMonth() {
        // 23:30 UTC on 31 January is already 1 February in Tokyo and still January in Los Angeles.
        val items = listOf(message("a", "2026-01-31T23:30:00Z"))
        assertEquals(YearMonth.of(2026, 2), GalleryGroups.byMonth(items, ZoneId.of("Asia/Tokyo")).single().month)
        assertEquals(YearMonth.of(2026, 1), GalleryGroups.byMonth(items, ZoneId.of("America/Los_Angeles")).single().month)
    }

    @Test fun noItemsNoGroups() = assertEquals(emptyList(), GalleryGroups.byMonth(emptyList(), ZoneId.of("UTC")))
}

class UploadCancelTest {
    @Test fun cancelRemovesTheMessageWhileQueuedOrUploading() {
        assertEquals(UploadCancel.Action.RemoveUnsent, UploadCancel.onCancel(UploadCancel.Phase.Queued))
        assertEquals(UploadCancel.Action.RemoveUnsent, UploadCancel.onCancel(UploadCancel.Phase.Uploading))
    }

    @Test fun cancelAfterSentKeepsTheMessage() = assertEquals(UploadCancel.Action.Keep, UploadCancel.onCancel(UploadCancel.Phase.Sent))
}

class SampleUploadCancelTest {
    private fun storeWith(vararg messages: Message) =
        SampleChatStore(SampleData.make(Instant.now()).let { d -> d.copy(chats = listOf(Chat("c1", "riya", ChatEncryption.EndToEnd, emptySet(), 0, messages.toList(), false))) })

    private fun photo(id: String, status: DeliveryStatus, sender: String = "me") =
        Message(id, sender, Instant.now(), MessageContent.Photo(5), status, null, emptyList())

    @Test fun cancelRemovesAnUploadingMessageOfMine() {
        val store = storeWith(photo("a", DeliveryStatus.Sent), photo("b", DeliveryStatus.Sending))
        store.cancelUpload("b")
        assertEquals(listOf("a"), store.chat("c1")!!.messages.map { it.id })
    }

    @Test fun cancelKeepsASentMessageAndSomeoneElses() {
        val store = storeWith(photo("a", DeliveryStatus.Sent), photo("b", DeliveryStatus.Sending, sender = "riya"))
        store.cancelUpload("a")
        store.cancelUpload("b")
        assertEquals(listOf("a", "b"), store.chat("c1")!!.messages.map { it.id })
    }
}
