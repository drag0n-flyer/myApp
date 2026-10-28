package org.example.app.messaging.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.MemberKey

class SafetyNumberTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun safetyMatchesVector() {
        val root = Json.parseToJsonElement(File("../../../docs/protocol/test-vectors/safety-v1.json").readText()).jsonObject
        val cases = root["cases"] as JsonArray
        assertEquals(6, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val members = o["members"]!!.jsonArray.map {
                MemberKey(it.jsonObject["identity"]!!.jsonPrimitive.content, hex(it.jsonObject["signatureKeyHex"]!!.jsonPrimitive.content))
            }
            val got = SafetyNumber.compute(members, o["accountA"]!!.jsonPrimitive.content, o["accountB"]!!.jsonPrimitive.content)
            assertEquals(o["expected"]!!.jsonArray.map { it.jsonPrimitive.content }, got, o["name"]!!.jsonPrimitive.content)
            assertEquals(12, got.size)
        }
    }

    @Test
    fun oversizedInputRejected() {
        assertFailsWith<IllegalArgumentException> {
            SafetyNumber.compute(listOf(MemberKey("a/d", ByteArray(65536))), "a", "b")
        }
    }
}
