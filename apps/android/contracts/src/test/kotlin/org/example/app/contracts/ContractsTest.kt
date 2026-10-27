package org.example.app.contracts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ContractsTest {
    @Test
    fun groupIdEqualityByContent() {
        val a = GroupId(byteArrayOf(1, 2))
        val b = GroupId(byteArrayOf(1, 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != GroupId(byteArrayOf(1, 3)))
    }

    @Test
    fun unpadsEverySharedVector() {
        val vectors = pushPaddingVectors()
        val cases = vectors["cases"]!!.jsonArray
        assertTrue(cases.size >= 7)
        for (case in cases) {
            val name = case.jsonObject["name"]!!.jsonPrimitive.content
            val padded = hex(case.jsonObject["paddedHex"]!!.jsonPrimitive.content)
            val payload = hex(case.jsonObject["payloadHex"]!!.jsonPrimitive.content)
            val unpadded = PushPadding.unpad(padded)
            assertNotNull(unpadded, name)
            // A payload over 3070 bytes travels as an empty one.
            assertContentEquals(if (payload.size > 3070) ByteArray(0) else payload, unpadded, name)
        }
        val oversize = cases.first { it.jsonObject["name"]!!.jsonPrimitive.content.contains("3071") }
        assertEquals(0, PushPadding.unpad(hex(oversize.jsonObject["paddedHex"]!!.jsonPrimitive.content))!!.size)
        assertEquals(3071, hex(oversize.jsonObject["payloadHex"]!!.jsonPrimitive.content).size)
    }

    @Test
    fun rejectsMalformedPadding() {
        for (case in pushPaddingVectors()["negative"]!!.jsonArray) {
            val name = case.jsonObject["name"]!!.jsonPrimitive.content
            assertNull(PushPadding.unpad(hex(case.jsonObject["paddedHex"]!!.jsonPrimitive.content)), name)
        }
        // Not a bucket size.
        assertNull(PushPadding.unpad(ByteArray(255)))
        // The length field reaches past the bucket.
        assertNull(PushPadding.unpad(ByteArray(256).also { it[0] = 0x01; it[1] = 0x00 }))
        assertNull(PushPadding.unpad(ByteArray(256).also { it[0] = 0x00; it[1] = 0xFF.toByte() }))
        // Non-zero padding byte, and a non-zero last byte.
        assertNull(PushPadding.unpad(ByteArray(256).also { it[1] = 1; it[2] = 9; it[100] = 1 }))
        assertNull(PushPadding.unpad(ByteArray(512).also { it[511] = 1 }))
        assertNull(PushPadding.unpad(ByteArray(0)))
        assertEquals(0, PushPadding.unpad(ByteArray(3072))!!.size)
    }

    @Test
    fun callsigCeilingIsString() {
        val json = Json
        val balanced = json.decodeFromString<CallSignalPayload>("""{"kind":"offer","callID":"c1","ceiling":"balanced"}""")
        assertEquals(DataMode.Balanced, balanced.ceiling)
        for ((name, mode) in listOf("ultraLow" to DataMode.UltraLow, "dataSaver" to DataMode.DataSaver, "high" to DataMode.High)) {
            assertEquals(mode, json.decodeFromString<CallSignalPayload>("""{"kind":"ring","callID":"c","ceiling":"$name"}""").ceiling)
        }
        assertNull(json.decodeFromString<CallSignalPayload>("""{"kind":"ring","callID":"c"}""").ceiling)
        val encoded = json.encodeToString(CallSignalPayload.serializer(), balanced)
        assertTrue(encoded.contains(""""ceiling":"balanced""""), encoded)
        assertEquals(balanced, json.decodeFromString<CallSignalPayload>(encoded))
        for (bad in listOf("2", "\"loud\"", "true", "{}")) {
            val result = runCatching { json.decodeFromString<CallSignalPayload>("""{"kind":"ring","callID":"c","ceiling":$bad}""") }
            assertTrue(result.isFailure, bad)
        }
    }

    @Test
    fun fakeEngineRoundTrip() = runTest {
        val alice = FakeMlsEngine("a1/d1")
        val bob = FakeMlsEngine("b1/d1")
        val group = alice.createGroup()
        val bundle = alice.add(bob.makeKeyPackages(1), group)
        alice.commitAccepted(group)
        assertEquals(1, bundle.newEpoch)
        assertEquals(1, alice.epoch(group))
        val joined = bob.process(assertNotNull(bundle.welcome))
        assertIs<Incoming.Joined>(joined)
        assertEquals(group, joined.group)
        assertEquals(listOf("a1/d1", "b1/d1"), bob.members(group))
        assertContentEquals(alice.exportSecret(group, "x", 40), bob.exportSecret(group, "x", 40))

        val encrypted = alice.encrypt("hello".toByteArray(), group)
        val received = bob.process(encrypted.ciphertext)
        assertIs<Incoming.Application>(received)
        assertEquals("a1/d1", received.sender)
        assertEquals("hello", String(received.plaintext))
        assertEquals(1, received.epoch)

        assertEquals(listOf("a1/d1", "b1/d1"), alice.memberKeys(group).map { it.identity })
        assertEquals("fake-sig:b1/d1", String(alice.memberKeys(group)[1].signatureKey))
    }

    @Test
    fun fakeEngineCommitsAndErrors() = runTest {
        val alice = FakeMlsEngine("a1/d1")
        val bob = FakeMlsEngine("b1/d1")
        val group = alice.createGroup()
        val add = alice.add(bob.makeKeyPackages(1), group)
        assertFailsWith<MlsException.PendingCommit> { alice.add(bob.makeKeyPackages(1), group) }
        alice.commitRejected(group)
        assertEquals(0, alice.epoch(group))
        val add2 = alice.add(bob.makeKeyPackages(1), group)
        alice.commitAccepted(group)
        bob.process(add2.welcome!!)
        assertFailsWith<MlsException.Duplicate> { bob.process(add2.welcome!!) }
        assertEquals(1, add.newEpoch)

        val remove = alice.remove(listOf("b1/d1"), group)
        alice.commitAccepted(group)
        val seen = bob.process(remove.commit)
        assertIs<Incoming.Commit>(seen)
        assertTrue(seen.removedMe)
        assertFailsWith<MlsException.UnknownGroup> { bob.epoch(group) }
        assertFailsWith<MlsException.Malformed> { bob.process(byteArrayOf(1, 2, 3)) }
        assertEquals(listOf("a1/d1"), alice.members(group))
    }

    @Test
    fun memorySecretStoreCopiesAndWipes() {
        val store = MemorySecretStore()
        val value = byteArrayOf(1, 2, 3)
        store.save(SecretNames.SESSION, value)
        value[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), store.load(SecretNames.SESSION))
        store.load(SecretNames.SESSION)!![0] = 7
        assertContentEquals(byteArrayOf(1, 2, 3), store.load(SecretNames.SESSION))
        store.save(SecretNames.DB_KEY, byteArrayOf(4))
        store.delete(SecretNames.SESSION)
        assertNull(store.load(SecretNames.SESSION))
        store.wipeAll()
        assertNull(store.load(SecretNames.DB_KEY))
    }

    private fun pushPaddingVectors(): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/protocol/test-vectors/push-padding.json").exists()) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "repo root not found" }, "docs/protocol/test-vectors/push-padding.json")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
