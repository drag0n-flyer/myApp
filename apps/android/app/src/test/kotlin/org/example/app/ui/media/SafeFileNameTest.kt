package org.example.app.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFileNameTest {
    private fun of(raw: String?) = SafeFileName.of(raw, "file")

    @Test fun anOrdinaryNameIsKept() = assertEquals("holiday photo (2).jpg", of("holiday photo (2).jpg"))

    @Test fun directoriesAreDropped() {
        assertEquals("passwd", of("../../etc/passwd"))
        assertEquals("evil.exe", of("..\\..\\evil.exe"))
        assertEquals("x.txt", of("/abs/path/x.txt"))
        assertEquals("x.txt", of("C:\\Users\\a\\x.txt"))
    }

    @Test fun nothingLeftGivesTheFallback() {
        for (raw in listOf(null, "", "   ", ".", "..", "...", "/", "../", "\u0000\u0001")) assertEquals("file", of(raw), "raw=$raw")
    }

    @Test fun controlAndDirectionCharactersAreRemoved() {
        assertEquals("abc.txt", of("a\u0000b\nc.txt"))
        // A right-to-left override can make "fdp.exe" read as "exe.pdf".
        assertEquals("invoicefdp.exe", of("invoice\u202Efdp.exe"))
    }

    @Test fun charactersFileSystemsRefuseAreRemoved() = assertEquals("a b.txt", of("a:*?\"<>| b.txt"))

    @Test fun leadingAndTrailingDotsAndSpacesAreTrimmed() {
        assertEquals("hidden", of(".hidden"))
        assertEquals("a.txt", of("a.txt. "))
    }

    @Test fun aLongNameIsShortenedAndKeepsItsExtension() {
        val name = of("x".repeat(300) + ".pdf")
        assertTrue(name.length <= 100, "length ${name.length}")
        assertTrue(name.endsWith(".pdf"))
    }

    @Test fun theResultIsNeverAPathOrADotName() {
        for (raw in listOf("a/b", "a\\b", "..", "./.", "x/..", "..\\..", "a\u2215b")) {
            val name = of(raw)
            assertFalse('/' in name || '\\' in name, "raw=$raw name=$name")
            assertTrue(name != "." && name != ".." && name.isNotEmpty(), "raw=$raw name=$name")
        }
    }
}
