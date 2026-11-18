package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig

private val JPEG = byteArrayOf(-1, -40, 1, 2, 3)

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
}
