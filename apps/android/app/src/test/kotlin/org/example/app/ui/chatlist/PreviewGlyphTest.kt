package org.example.app.ui.chatlist

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.app.R
import org.example.app.core.chats.MessagePreview
import org.junit.Test

class PreviewGlyphTest {
    @Test
    fun callsVoiceAndPhotosGetAnIcon() {
        assertEquals(PreviewGlyph(R.drawable.ic_mic, false), previewGlyph(MessagePreview.Resource("preview_voice", listOf("0:14"))))
        assertEquals(PreviewGlyph(R.drawable.ic_image, false), previewGlyph(MessagePreview.Resource("preview_photo")))
        assertEquals(PreviewGlyph(R.drawable.ic_call, true), previewGlyph(MessagePreview.Resource("preview_missed_audio_call")))
        assertEquals(PreviewGlyph(R.drawable.ic_videocam, true), previewGlyph(MessagePreview.Resource("preview_missed_video_call")))
        assertEquals(PreviewGlyph(R.drawable.ic_call, false), previewGlyph(MessagePreview.Resource("preview_audio_call", listOf("3"))))
        assertEquals(PreviewGlyph(R.drawable.ic_videocam, false), previewGlyph(MessagePreview.Resource("preview_video_call", listOf("12"))))
    }

    @Test
    fun plainTextHasNoIcon() {
        assertNull(previewGlyph(MessagePreview.Text("hi")))
        assertNull(previewGlyph(null))
        assertNull(previewGlyph(MessagePreview.Resource("preview_deleted")))
    }
}
