package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMediaTest {
    @Test fun isVideoByExtensionOrMime() {
        assertTrue(ChatMedia.isVideo("a.MP4", "application/octet-stream"))
        assertTrue(ChatMedia.isVideo("a.bin", "video/mp4"))
        assertTrue(ChatMedia.isVideo("a.bin", "VIDEO/QuickTime"))
        for (ext in listOf("mp4", "m4v", "mov", "3gp", "webm", "mkv", "MOV", "WebM")) assertTrue(ChatMedia.isVideo("clip.$ext", ""), ext)
        assertFalse(ChatMedia.isVideo("a.jpg", "image/jpeg"))
        assertFalse(ChatMedia.isVideo("mov", ""))
        assertFalse(ChatMedia.isVideo("mp4", "application/octet-stream"))
        assertFalse(ChatMedia.isVideo("", ""))
        assertFalse(ChatMedia.isVideo("report.pdf", "application/pdf"))
    }

    @Test fun aVideoGetsARealNameAndMime() {
        assertEquals("holiday.mov", ChatMedia.videoFileName("holiday.mov", "video/quicktime"))
        assertEquals("holiday.mp4", ChatMedia.videoFileName("holiday", "video/mp4"))
        assertEquals("video.mov", ChatMedia.videoFileName(null, "video/quicktime"))
        assertEquals("video.webm", ChatMedia.videoFileName("  ", "video/webm"))
        assertEquals("video.mp4", ChatMedia.videoFileName(null, null))
        assertEquals("video.mp4", ChatMedia.videoFileName(null, "video/unknown"))
        assertEquals("clip.mkv", ChatMedia.videoFileName("clip.mkv", null))
        assertEquals("video/quicktime", ChatMedia.videoMime("a.MOV"))
        assertEquals("video/mp4", ChatMedia.videoMime("a"))
        assertEquals("video/3gpp", ChatMedia.videoMime("a.3gp"))
    }

    @Test fun aFileContentIsAVideoByItsOwnNameAndMime() {
        assertTrue(MessageContent.File("a.mp4", 1).isVideo)
        assertTrue(MessageContent.File("a", 1, "video/mp4").isVideo)
        assertFalse(MessageContent.File("a.txt", 1, "text/plain").isVideo)
    }
}
