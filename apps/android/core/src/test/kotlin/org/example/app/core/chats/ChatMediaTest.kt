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

    @Test fun aVideoTravelsUnderAGenericNameWithTheRightExtension() {
        // The picker's name (it can reveal the device and the time) is never used; only its extension is.
        assertEquals("video-1a2b3c4d.mov", ChatMedia.videoFileName("PXL_20260101_120000.MOV", "video/quicktime", "1a2b3c4d"))
        assertEquals("video-1a2b3c4d.mp4", ChatMedia.videoFileName("holiday", "video/mp4", "1a2b3c4d"))
        assertEquals("video-1a2b3c4d.mov", ChatMedia.videoFileName(null, "video/quicktime", "1a2b3c4d"))
        assertEquals("video-1a2b3c4d.webm", ChatMedia.videoFileName("  ", "video/webm", "1a2b3c4d"))
        assertEquals("video-1a2b3c4d.mp4", ChatMedia.videoFileName(null, null, "1a2b3c4d"))
        assertEquals("video-1a2b3c4d.mp4", ChatMedia.videoFileName(null, "video/unknown", "1a2b3c4d"))
        assertEquals("video-1a2b3c4d.mkv", ChatMedia.videoFileName("clip.mkv", null, "1a2b3c4d"))
        assertTrue(Regex("video-[0-9a-f]{8}\\.mp4").matches(ChatMedia.videoFileName(null, null)))
        assertEquals("video/quicktime", ChatMedia.videoMime("a.MOV"))
        assertEquals("video/mp4", ChatMedia.videoMime("a"))
        assertEquals("video/3gpp", ChatMedia.videoMime("a.3gp"))
        assertEquals("mov", ChatMedia.videoExtension("a.MOV"))
        assertEquals("mp4", ChatMedia.videoExtension("a.bin"))
        assertEquals("mp4", ChatMedia.videoExtension("a"))
    }

    @Test fun aFileContentIsAVideoByItsOwnNameAndMime() {
        assertTrue(MessageContent.File("a.mp4", 1).isVideo)
        assertTrue(MessageContent.File("a", 1, "video/mp4").isVideo)
        assertFalse(MessageContent.File("a.txt", 1, "text/plain").isVideo)
    }
}
