package org.example.app.messaging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.CallSealException
import org.example.app.contracts.CallSignalPayload
import org.example.app.contracts.FakeMlsEngine
import org.example.app.contracts.GroupId
import org.example.app.contracts.MlsEngine
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice
import org.example.app.messaging.testing.InMemoryRepository
import org.example.app.messaging.testing.SpyMlsEngine
import org.example.app.messaging.testing.TestMlsLock

fun TestDevice.sealer() = ChatCallSealer(mls, repo, lock, clock, manager, handler)

private fun oneToOne(g: GroupId, chatId: String) =
    org.example.app.messaging.ports.ConversationRecord("conv-1", chatId, g.raw, "e2ee", "e2ee", false, 1, true, NOW)

private val ring = CallSignalPayload(kind = CallSignalPayload.Kind.ring, callID = "call-1", video = true)

class ChatCallSealerTest {
    private suspend fun joined(): Rig {
        val rig = Rig().start()
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return rig
    }

    @Test
    fun openReturnsNonNullSenderDevice() = runTest {
        val rig = joined()
        val sealed = rig.ana1.sealer().seal(ring, "acc-bob")
        val opened = rig.bob1.sealer().open(sealed, "acc-ana")
        assertEquals("ana1", opened.senderDeviceID)
        assertEquals("call-1", opened.payload.callID)
        rig.assertLockDiscipline()
    }

    @Test
    fun wrongSenderThrows() = runTest {
        val rig = joined()
        val sealed = rig.ana1.sealer().seal(ring, "acc-bob")
        assertFailsWith<CallSealException.WrongSender> { rig.bob1.sealer().open(sealed, "acc-someone-else") }
    }

    /** A ring counts only in the 1:1 conversation with its sender (calls.md): not in a group held with someone else. */
    @Test
    fun aRingInAnotherConversationIsWrongSender() = runTest {
        val rig = joined()
        val sealed = rig.ana1.sealer().seal(ring, "acc-bob")
        // On Bob's side, this group is now the conversation with Carol, not with Ana.
        val conv = rig.bob1.repo.preferredConversation("acc-ana")!!
        rig.bob1.repo.upsertConversation(conv.copy(chatId = "acc-carol"))
        assertFailsWith<CallSealException.WrongSender> { rig.bob1.sealer().open(sealed, "acc-ana") }
    }

    /** No conversation row yet: the group itself must be the 1:1 one (only this account and the sender's). */
    @Test
    fun aRingInAnUnrecordedGroupWithAThirdAccountIsWrongSender() = runTest {
        val a = FakeMlsEngine("acc-a/d1")
        val bob = FakeMlsEngine("acc-bob/bob1")
        val carol = FakeMlsEngine("acc-carol/c1")
        val g = a.createGroup()
        val add = a.add(bob.makeKeyPackages(1) + carol.makeKeyPackages(1), g); a.commitAccepted(g)
        bob.process(add.welcome!!)
        val frame = a.encrypt(ContentCodec.encode(ContentEnvelope.CallSig("id", NOW, ring)), g).ciphertext
        val rig = Rig().start()
        val sealer = ChatCallSealer(bob, InMemoryRepository(), TestMlsLock(), rig.bob1.clock, rig.bob1.manager, rig.bob1.handler)
        assertFailsWith<CallSealException.WrongSender> { sealer.openRecoveringSender(frame) }
    }

    @Test
    fun aRingInAnUnrecordedOneToOneGroupOpens() = runTest {
        val a = FakeMlsEngine("acc-a/d1")
        val bob = FakeMlsEngine("acc-bob/bob1")
        val g = a.createGroup()
        val add = a.add(bob.makeKeyPackages(1), g); a.commitAccepted(g)
        bob.process(add.welcome!!)
        val frame = a.encrypt(ContentCodec.encode(ContentEnvelope.CallSig("id", NOW, ring)), g).ciphertext
        val rig = Rig().start()
        val sealer = ChatCallSealer(bob, InMemoryRepository(), TestMlsLock(), rig.bob1.clock, rig.bob1.manager, rig.bob1.handler)
        assertEquals("acc-a", sealer.openRecoveringSender(frame).senderAccountId)
    }

    @Test
    fun replayIsUndecryptable() = runTest {
        val rig = joined()
        val sealed = rig.ana1.sealer().seal(ring, "acc-bob")
        rig.bob1.sealer().open(sealed, "acc-ana")
        assertFailsWith<CallSealException.Undecryptable> { rig.bob1.sealer().open(sealed, "acc-ana") }
    }

    /** Sender identity without a `/`. A standalone pair of engines: receiver b/1, sender "justanaccount". */
    @Test
    fun identityWithoutSlashIsWrongSender() = runTest {
        val sender = FakeMlsEngine("justanaccount")
        val receiver = FakeMlsEngine("acc-b/d1")
        val g = sender.createGroup()
        val add = sender.add(receiver.makeKeyPackages(1), g)
        sender.commitAccepted(g)
        receiver.process(add.welcome!!)
        val frame = sender.encrypt(ContentCodec.encode(ContentEnvelope.CallSig("id", NOW, ring)), g).ciphertext
        val rig = Rig().start()
        val sealer = ChatCallSealer(receiver, InMemoryRepository(), TestMlsLock(), rig.bob1.clock, rig.bob1.manager, rig.bob1.handler)
        assertFailsWith<CallSealException.WrongSender> { sealer.open(frame, "justanaccount") }
    }

    /** Sender identity with an empty device part (`acc/`). */
    @Test
    fun identityWithEmptyDevicePartIsWrongSender() = runTest {
        val sender = FakeMlsEngine("acc-a/")
        val receiver = FakeMlsEngine("acc-b/d1")
        val g = sender.createGroup()
        val add = sender.add(receiver.makeKeyPackages(1), g)
        sender.commitAccepted(g)
        receiver.process(add.welcome!!)
        val frame = sender.encrypt(ContentCodec.encode(ContentEnvelope.CallSig("id", NOW, ring)), g).ciphertext
        val rig = Rig().start()
        val sealer = ChatCallSealer(receiver, InMemoryRepository(), TestMlsLock(), rig.bob1.clock, rig.bob1.manager, rig.bob1.handler)
        assertFailsWith<CallSealException.WrongSender> { sealer.open(frame, "acc-a") }
    }

    @Test
    fun sealerPassesTheMessagesOwnGroupToTheEngine() = runTest {
        val a = FakeMlsEngine("acc-a/d1")
        val bob = FakeMlsEngine("acc-b/d1")
        val g = a.createGroup()
        val add = a.add(bob.makeKeyPackages(1), g); a.commitAccepted(g)
        bob.process(add.welcome!!)
        val frame = a.encrypt(ContentCodec.encode(ContentEnvelope.CallSig("id", NOW, ring)), g).ciphertext
        val rig = Rig().start()
        val spy = SpyMlsEngine(bob) { true }
        val repo = InMemoryRepository().also { it.upsertConversation(oneToOne(g, "acc-a")) }
        val sealer = ChatCallSealer(spy, repo, TestMlsLock(), rig.bob1.clock, rig.bob1.manager, rig.bob1.handler)
        // The sealer hands the engine the message's own group, so a normal frame opens.
        assertEquals("call-1", sealer.openRecoveringSender(frame).payload.callID)
        assertEquals(1, spy.processApplicationCalls)
    }

    @Test
    fun commitIsRejectedAndStateStaysUnchanged() = runTest {
        val a = FakeMlsEngine("acc-a/d1")
        val bobLock = TestMlsLock()
        val bob = SpyMlsEngine(FakeMlsEngine("acc-b/d1")) { bobLock.isHeld }
        val cy = FakeMlsEngine("acc-c/d1")
        val g = a.createGroup()
        val add = a.add(bob.makeKeyPackages(1), g); a.commitAccepted(g)
        bob.process(add.welcome!!)
        val commit = a.add(cy.makeKeyPackages(1), g).commit
        val rig = Rig().start()
        val repo = InMemoryRepository()
        val sealer = ChatCallSealer(bob, repo, bobLock, rig.bob1.clock, rig.bob1.manager, rig.bob1.handler)
        val before = bob.epoch(g)
        assertFailsWith<CallSealException.Undecryptable> { sealer.openRecoveringSender(commit) }
        assertFailsWith<CallSealException.Undecryptable> { sealer.openRecoveringSender(add.welcome!!) }
        assertEquals(before, bob.epoch(g))
        assertEquals(1, bob.processCalls, "only the setup Welcome: never the general process for a call frame")
        assertTrue(!repo.isProcessed(org.example.app.messaging.ports.PayloadHash.of(commit)))
        // The commit still applies afterwards, as it would from the inbox.
        bob.process(commit)
        assertEquals(before + 1, bob.epoch(g))
    }

    @Test
    fun applicationMessageThatIsNotACallIsStoredNotLost() = runTest {
        val rig = joined()
        val conv = rig.ana1.repo.preferredConversation("acc-bob")!!
        val text = ContentCodec.encode(ContentEnvelope.Text("m-1", NOW, "hello"))
        val ct = rig.ana1.lock.withLock { rig.ana1.mls.encrypt(text, GroupId(conv.groupId)) }.ciphertext
        assertFailsWith<CallSealException.Undecryptable> { rig.bob1.sealer().open(ct, "acc-ana") }
        assertNotNull(rig.bob1.repo.message("m-1"))
    }

    @Test
    fun sealUsesPreferredConversation() = runTest {
        val rig = joined()
        val first = rig.ana1.repo.preferredConversation("acc-bob")!!
        val ids = rig.server.conversationIds()
        val posted = rig.server.messages.size
        val sealed = rig.ana1.sealer().seal(ring, "acc-bob")
        assertEquals(ids, rig.server.conversationIds(), "no new conversation")
        assertEquals(posted, rig.server.messages.size, "nothing posted")
        assertEquals(first.serverId, rig.ana1.repo.preferredConversation("acc-bob")!!.serverId)
        // Only a member of that group can read it, and it is a call signal.
        val opened = rig.bob1.sealer().openRecoveringSender(sealed)
        assertEquals("call-1", opened.payload.callID)
        assertEquals("acc-ana", opened.senderAccountId)
        rig.assertLockDiscipline()
    }

    @Test
    fun sealCreatesTheConversationWhenThereIsNone() = runTest {
        val rig = Rig().start()
        assertTrue(rig.server.conversationIds().isEmpty())
        rig.ana1.sealer().seal(ring, "acc-bob")
        assertEquals(1, rig.server.conversationIds().size)
    }
}
