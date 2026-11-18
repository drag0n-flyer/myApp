package org.example.app.core.chats

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilePreviewTest {
    private fun preview(name: String) =
        Message("x", "a", Instant.EPOCH, MessageContent.File(name, 10), DeliveryStatus.Sent, null, emptyList()).preview

    @Test fun anOrdinaryNameIsShownAsIs() = assertEquals(MessagePreview.Text("report.pdf"), preview("report.pdf"))

    @Test fun aDirectionOverrideNameIsShownCleaned() =
        assertEquals(MessagePreview.Text("reportfdp.html"), preview("report‮fdp.html"))

    @Test fun anEndlessNameIsShortened() {
        val text = (preview("x".repeat(5_000) + ".pdf") as MessagePreview.Text).text
        assertTrue(text.length <= SafeFileName.MAX_LENGTH && text.endsWith(".pdf"))
    }
}
