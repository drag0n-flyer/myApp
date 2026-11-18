package org.example.app.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarColorTest {
    // Expected values computed independently in Python (FNV-1a 32: basis 2166136261, prime 16777619, over UTF-8 bytes):
    // "a" -> 3826002220, "acc-ana" -> 1260187061, the UUID -> 2080594418, "" -> 2166136261; each mod 8.
    @Test fun knownIdsKeepTheirColour() {
        assertEquals(4, AvatarColor.index("a"))
        assertEquals(5, AvatarColor.index("acc-ana"))
        assertEquals(2, AvatarColor.index("3f2b8c1e-9d4a-4e7b-a1c5-0b6d7e8f9a12"))
        assertEquals(5, AvatarColor.index(""))
    }

    @Test fun usesUtf8BytesNotUtf16Units() {
        // "é" is 0xC3 0xA9 in UTF-8; FNV-1a 32 of those bytes is 513665217 (Python), mod 8 = 1.
        assertEquals(1, AvatarColor.index("é"))
    }

    @Test fun alwaysInRange() {
        for (i in 0 until 2000) assertTrue(AvatarColor.index("account-$i") in 0 until AvatarColor.COUNT)
        assertEquals(8, AvatarColor.COUNT)
    }
}
