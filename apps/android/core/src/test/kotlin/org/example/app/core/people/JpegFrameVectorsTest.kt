package org.example.app.core.people

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The frame-header vectors shared with AppCore: server/internal/vectors/profile_photo.json (ruling A, the hostile photo). */
class JpegFrameVectorsTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun matchesSharedVectors() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "server/internal/vectors/profile_photo.json").exists()) dir = dir.parentFile
        val file = File(requireNotNull(dir) { "repository root not found" }, "server/internal/vectors/profile_photo.json")
        val cases = Json.parseToJsonElement(file.readText()).jsonArray
        assertTrue(cases.size >= 24)
        for (c in cases.map { it.jsonObject }) {
            val name = c.getValue("name").jsonPrimitive.content
            val bytes = hex(c.getValue("hex").jsonPrimitive.content)
            val accept = c.getValue("accept").jsonPrimitive.boolean
            assertEquals(accept, ProfilePhotoRule.isAcceptable(bytes), "accepted: $name")
            val w = c["width"]?.jsonPrimitive?.int
            val h = c["height"]?.jsonPrimitive?.int
            if (w != null && h != null) assertEquals(w to h, JpegFrame.dimensions(bytes), "dimensions: $name")
        }
    }

    @Test fun anAcceptablePhotoNeedsAtLeastFourBytes() {
        assertEquals(false, ProfilePhotoRule.isAcceptable(byteArrayOf(-1, -40, -1)))
        assertEquals(false, ProfilePhotoRule.isAcceptable(byteArrayOf(-1, -40)))
    }
}
