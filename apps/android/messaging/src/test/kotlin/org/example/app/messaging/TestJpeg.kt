package org.example.app.messaging

/** Bytes that pass the profile-photo rule: a JPEG start with a baseline frame header of [w] x [h], padded to [size]. */
object TestJpeg {
    fun of(w: Int = 256, h: Int = w, size: Int = 40, marker: Int = 0xC0): ByteArray {
        val head = byteArrayOf(
            -1, -40, -1, marker.toByte(), 0, 11, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 1, 1, 0x11, 0,
        )
        return head.copyOf(maxOf(size, head.size))
    }
}
