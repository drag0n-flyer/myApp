package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig

private val JPEG = TestJpeg.of()

/** A received `profile` is applied only for the chat's own peer, and a photo that does not decode is ignored whole (task P5). */
class ProfileReceiveTest {
    private suspend fun setup(): Pair<Rig, org.example.app.messaging.ports.ConversationRecord> {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return rig to conv
    }

    @Test fun aPhotoThatDoesNotDecodeIgnoresTheWholeProfile() = runTest {
        val (rig, conv) = setup()
        rig.bob1.photoDecodes = { false }
        rig.ana1.send(conv, ContentEnvelope.Profile("p1", NOW, "Ann", JPEG, 1))
        rig.bob1.processor.sync()
        assertNull(rig.bob1.repo.profile("acc-ana"), "name and photo are both dropped")
        // A profile with no photo has nothing to decode.
        rig.ana1.send(conv, ContentEnvelope.Profile("p2", NOW + 1, "Ann", null, 2))
        rig.bob1.processor.sync()
        assertEquals("Ann", rig.bob1.repo.profile("acc-ana")!!.name)
    }

    @Test fun aDecodablePhotoIsKept() = runTest {
        val (rig, conv) = setup()
        rig.bob1.photoDecodes = { it.contentEquals(JPEG) }
        rig.ana1.send(conv, ContentEnvelope.Profile("p1", NOW, "Ann", JPEG, 1))
        rig.bob1.processor.sync()
        assertEquals(1, rig.bob1.repo.profile("acc-ana")!!.ver)
    }

    // Ruling A: the declared size is read from the frame header before anything is stored.

    @Test fun aHostilePhotoIsIgnoredWholeBeforeItIsStored() = runTest {
        val (rig, conv) = setup()
        rig.ana1.send(conv, ContentEnvelope.Profile("h1", NOW, "Eve", TestJpeg.of(65000, 65000), 1))
        rig.ana1.send(conv, ContentEnvelope.Profile("h2", NOW + 1, "Eve", TestJpeg.of(1025, 1), 2))
        rig.ana1.send(conv, ContentEnvelope.Profile("h3", NOW + 2, "Eve", byteArrayOf(-1, -40, 1, 2, 3), 3)) // no frame header
        rig.bob1.processor.sync()
        assertNull(rig.bob1.repo.profile("acc-ana"))
        rig.ana1.send(conv, ContentEnvelope.Profile("ok", NOW + 3, "Ann", TestJpeg.of(1024, 1024), 4))
        rig.bob1.processor.sync()
        assertEquals(4, rig.bob1.repo.profile("acc-ana")!!.ver)
    }

    // Ruling B

    @Test fun aProfileFromABlockedAccountIsNotStored() = runTest {
        val (rig, conv) = setup()
        rig.bob1.repo.block("acc-ana", NOW)
        rig.ana1.send(conv, ContentEnvelope.Profile("p1", NOW, "Ann", null, 1))
        rig.bob1.processor.sync()
        assertNull(rig.bob1.repo.profile("acc-ana"))
    }

    // Ruling E: a profile from my own other device replaces my stored profile and nobody is sent it again.

    @Test fun myOtherDeviceProfileIsAdoptedWithoutSendingAnything() = runTest {
        val (rig, conv) = setup()
        rig.bob2.processor.sync()
        rig.bob1.repo.upsertContact(ContactRecord("acc-bob", "bob", "Bob", false, NOW))
        rig.bob1.repo.set("profile.ver", "3".encodeToByteArray())
        rig.bob2.send(conv, ContentEnvelope.Profile("o1", NOW, "Bobby", JPEG, 1_700_000_000))
        val before = rig.server.pending("acc-ana/ana1").size // bob2's own post is in there already
        rig.bob1.processor.sync()
        assertEquals("1700000000", rig.bob1.repo.get("profile.ver")!!.decodeToString())
        assertContentEquals(JPEG, rig.bob1.repo.get("profile.photo"))
        assertEquals("Bobby", rig.bob1.repo.contact("acc-bob")?.displayName)
        assertEquals("1700000000", rig.bob1.repo.get("profile-sent:acc-ana")!!.decodeToString())
        assertEquals(before, rig.server.pending("acc-ana/ana1").size)
        // A lower or equal version from the other device changes nothing.
        rig.bob2.send(conv, ContentEnvelope.Profile("o2", NOW + 1, "Old", null, 1_700_000_000))
        rig.bob2.send(conv, ContentEnvelope.Profile("o3", NOW + 2, "Older", null, 5))
        rig.bob1.processor.sync()
        assertEquals("Bobby", rig.bob1.repo.contact("acc-bob")?.displayName)
        assertContentEquals(JPEG, rig.bob1.repo.get("profile.photo"))
    }

    @Test fun myOtherDevicesHostilePhotoIsNotAdopted() = runTest {
        val (rig, conv) = setup()
        rig.bob2.processor.sync()
        rig.bob2.send(conv, ContentEnvelope.Profile("o1", NOW, "Bobby", TestJpeg.of(5000, 5000), 9))
        rig.bob1.processor.sync()
        assertNull(rig.bob1.repo.get("profile.ver"))
    }
}
