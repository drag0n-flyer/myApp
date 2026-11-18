package org.example.app.core.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The vectors shared with the server and AppCore: server/internal/vectors/display_names.json. */
class DisplayNameVectorsTest {
    @Test fun matchesSharedVectors() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "server/internal/vectors/display_names.json").exists()) dir = dir.parentFile
        val file = File(requireNotNull(dir) { "repository root not found" }, "server/internal/vectors/display_names.json")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 50)
        for (i in 0 until cases.size) {
            val c = cases[i].jsonObject
            val input = c.getValue("input").jsonPrimitive.content
            val want = c.getValue("clean").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(want?.codePoints()?.toArray()?.toList(), DisplayNameRule.clean(input)?.codePoints()?.toArray()?.toList(), "case $i: ${input.codePoints().toArray().map { Integer.toHexString(it) }}")
        }
    }
}
