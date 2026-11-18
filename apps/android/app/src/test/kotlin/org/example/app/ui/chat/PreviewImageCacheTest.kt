package org.example.app.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PreviewImageCacheTest {
    @Test fun r3APictureIsDecodedOnceAndThenServedFromTheCache() {
        val cache = PreviewImageCache<String>(4)
        var decodes = 0
        val bytes = byteArrayOf(1, 2, 3)
        val first = cache.get(bytes) { decodes++; "bitmap" }
        val second = cache.get(byteArrayOf(1, 2, 3)) { decodes++; "other" } // same content, another array
        assertSame(first, second)
        assertEquals(1, decodes)
    }

    @Test fun aPictureThatCannotBeDecodedIsRememberedAsNone() {
        val cache = PreviewImageCache<String>(4)
        var decodes = 0
        assertNull(cache.get(byteArrayOf(9)) { decodes++; null })
        assertNull(cache.get(byteArrayOf(9)) { decodes++; null })
        assertEquals(1, decodes)
    }

    @Test fun theCacheIsBoundedAndForgetsTheOldest() {
        val cache = PreviewImageCache<Int>(2)
        var decodes = 0
        for (n in 1..3) cache.get(byteArrayOf(n.toByte())) { decodes++; n }
        cache.get(byteArrayOf(1)) { decodes++; 1 } // was pushed out
        assertEquals(4, decodes)
    }
}
