package org.example.app.core.chats

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Previews (chat list, notifications, quotes, search snippets) show the plain text; the stored text is untouched. */
class FormattedPreviewTest {
    private val typed = "*hi* _there_ 2*3*4"

    private fun message() = Message("m", "a", Instant.EPOCH, MessageContent.Text(typed), DeliveryStatus.Read, null, emptyList())

    @Test fun previewDropsTheMarkers() = assertEquals(MessagePreview.Text("hi there 2*3*4"), message().preview)

    @Test fun storedTextIsAsTyped() = assertEquals(MessageContent.Text(typed), message().content)

    @Test fun sampleSearchSnippetIsPlainAndFindsTheTypedText() {
        val store = SampleChatStore()
        val chat = store.chats.value.first()
        store.send(MessageContent.Text("find *zebra* here"), chat.id, null)
        val hit = store.searchMessages("*zebra*", 10).single()
        assertEquals("find zebra here", hit.preview)
    }
}
