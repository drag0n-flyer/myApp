package org.example.app.ui.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSaverTest {
    private val none = File("does-not-exist")

    @Test fun aVideoIsSavedWithTheMimeItsNameGives() {
        assertEquals("video/quicktime" to true, MediaSaver.kindOf(none, "video-1a2b3c4d.mov"))
        assertEquals("video/webm" to true, MediaSaver.kindOf(none, "video-1a2b3c4d.webm"))
        assertEquals("video/x-matroska" to true, MediaSaver.kindOf(none, "video-1a2b3c4d.mkv"))
        assertEquals("video/mp4" to true, MediaSaver.kindOf(none, "video-1a2b3c4d.mp4"))
        assertEquals("video/mp4" to true, MediaSaver.kindOf(none, "video-1a2b3c4d"))
    }
}
