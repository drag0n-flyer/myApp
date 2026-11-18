package org.example.app.core.people

/**
 * Reads the declared pixel size of a JPEG from its frame header without decoding anything.
 *
 * Rule: the file starts with FF D8. Then segments are walked by their lengths (a marker is FF, any number of FF fill
 * bytes, then the marker byte; markers D0..D7 and 01 have no length; a segment length is two bytes, counts itself and
 * must be at least 2 and fit the file). The first SOF0, SOF1 or SOF2 (FF C0, FF C1, FF C2) gives the size: its length
 * must be at least 8 and fit the file; the bytes after the precision are height then width, 16 bits each. The walk stops
 * with null at the start of a scan (FF DA), at the end of the image (FF D9), at a truncated segment or at the end of the
 * file, so an SOF-looking byte pair inside another segment (an EXIF block, a thumbnail) is never mistaken for the header.
 * Other frame types (lossless, arithmetic, hierarchical) are not read: null.
 */
object JpegFrame {
    /** (width, height) as declared, or null when no frame header is found or the data is truncated. Either side may be 0. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++ // fill bytes
            if (i >= bytes.size) return null
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0x00) return null
            if (marker in 0xD0..0xD7 || marker == 0x01) continue
            if (marker == 0xD9 || marker == 0xDA) return null
            if (i + 2 > bytes.size) return null
            val length = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (length < 2 || i + length > bytes.size) return null
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                if (length < 8) return null
                val height = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                val width = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                return width to height
            }
            i += length
        }
        return null
    }
}
