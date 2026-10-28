package org.example.app.messaging.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.GroupId
import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.FolderRecord
import org.example.app.messaging.ports.ConversationRecord

class DeviceSyncTest {
    /** ana1 created the conversation with bob; ana2, bob1 and bob2 joined it. */
    private suspend fun joined(): Pair<Rig, ConversationRecord> {
        val rig = Rig().start()
        val record = rig.ana1.manager.create("acc-bob")
        listOf(rig.ana2, rig.bob1, rig.bob2).forEach { it.processor.sync() }
        return rig to record
    }

    private suspend fun Rig.membersOn(d: TestDevice, record: ConversationRecord) = d.mls.members(GroupId(record.groupId))

    @Test
    fun revokedDeviceRemovedWithin24h() = runTest {
        val (rig, record) = joined()
        // ana1 was online when the server revoked ana2: the event removes it at once.
        rig.server.revokeDevice("acc-ana", "ana2")
        rig.ana1.deviceSync.handleRevoked("ana2")
        assertFalse("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
        assertEquals(2L, rig.ana1.repo.conversation(record.serverId)!!.epoch)

        // The peer sees an ordinary commit and a device line.
        rig.bob1.processor.sync()
        assertFalse("acc-ana/ana2" in rig.bob1.mls.members(GroupId(record.groupId)))
        val line = rig.bob1.repo.messages("acc-ana", null, 50).single { it.id.contains("remove-acc-ana/ana2") }
        assertEquals("""{"deviceRemoved":{"name":"acc-ana"}}""", line.envelope.decodeToString())
        rig.assertLockDiscipline()
    }

    @Test
    fun deviceThatMissedTheRevocationCatchesUpWhenItComesOnline() = runTest {
        val (rig, record) = joined()
        // ana1 was offline for the event; now it compares the group's leaves with GET /v1/devices.
        rig.server.revokeDevice("acc-ana", "ana2")
        rig.ana1.deviceSync.reconcileOwnDevices()
        rig.ana1.nowMs += 10 * 60_000L
        rig.ana1.deviceSync.reconcileOwnDevices()
        assertFalse("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
        assertTrue("acc-ana/ana1" in rig.membersOn(rig.ana1, record))
        // Nothing left to do: a second pass posts no commit.
        val commits = rig.server.commitPosts.size
        rig.ana1.deviceSync.reconcileOwnDevices()
        assertEquals(commits, rig.server.commitPosts.size)
        rig.assertLockDiscipline()
    }

    @Test
    fun revokingAnAlreadyRemovedDeviceDoesNothing() = runTest {
        val (rig, record) = joined()
        rig.ana1.deviceSync.handleRevoked("ana2")
        val commits = rig.server.commitPosts.size
        rig.ana1.deviceSync.handleRevoked("ana2")
        assertEquals(commits, rig.server.commitPosts.size)
        assertEquals(record.serverId, rig.ana1.repo.preferredConversation("acc-bob")?.serverId)
    }

    @Test
    fun newOwnDeviceNotifies() = runTest {
        val (rig, record) = joined()
        val ana3 = TestDevice(rig.server, "acc-ana", "ana3", rig.escrowKeys.publicKey)
        ana3.api.uploadKeyPackages(ana3.mls.makeKeyPackages(3))

        rig.ana1.deviceSync.reconcileOwnDevices()
        assertTrue("acc-ana/ana3" in rig.membersOn(rig.ana1, record))

        // Another own device sees the commit and notices once, with the name the server lists.
        rig.ana2.processor.sync()
        assertEquals(listOf("ana3" to "ana3"), rig.ana2.ownDeviceNotices)
        rig.ana2.processor.sync()
        assertEquals(1, rig.ana2.ownDeviceNotices.size, "one notice per device, ever")
        // The device that added it, and the new device itself, say nothing.
        rig.ana1.processor.sync()
        ana3.processor.sync()
        assertEquals(emptyList(), rig.ana1.ownDeviceNotices)
        assertEquals(emptyList(), ana3.ownDeviceNotices)
        // The peer sees "a device was added" by account, never a name.
        rig.bob1.processor.sync()
        val line = rig.bob1.repo.messages("acc-ana", null, 50).single { it.id.contains("add-acc-ana/ana3") }
        assertEquals("""{"deviceAdded":{"name":"acc-ana"}}""", line.envelope.decodeToString())
        // The new device learned the mode again from the committer.
        assertEquals("e2ee", ana3.repo.preferredConversation("acc-bob")?.innerMode)
        rig.assertLockDiscipline()
    }

    @Test
    fun failedNoticeStaysPendingAndIsRetried() = runTest {
        val (rig, _) = joined()
        val ana3 = TestDevice(rig.server, "acc-ana", "ana3", rig.escrowKeys.publicKey)
        ana3.api.uploadKeyPackages(ana3.mls.makeKeyPackages(3))
        rig.ana1.deviceSync.reconcileOwnDevices()
        rig.server.onCall = { identity, call ->
            if (identity == "acc-ana/ana2" && call == "devices") throw org.example.app.contracts.ServerException(503, "unavailable", null, null)
        }
        rig.ana2.processor.sync()
        assertEquals(emptyList(), rig.ana2.ownDeviceNotices)
        rig.server.onCall = null
        rig.ana2.processor.drain()
        rig.ana2.processor.sync()
        assertEquals(listOf("ana3" to "ana3"), rig.ana2.ownDeviceNotices)
    }

    @Test
    fun revokedSelfWipesAndPostsNoCommit() = runTest {
        val (rig, _) = joined()
        val commits = rig.server.commitPosts.size
        rig.ana2.deviceSync.handleRevoked("ana2")
        assertTrue(rig.ana2.repo.chats().isEmpty())
        assertEquals(listOf("mls", "signingKey"), rig.ana2.wipes)
        assertEquals(commits, rig.server.commitPosts.size)
    }

    @Test
    fun snapshotRoundTrip() = runTest {
        val (rig, _) = joined()
        val repo = rig.ana1.repo
        repo.upsertContact(ContactRecord("acc-bob", "bob", "Bob", true, 1234))
        repo.upsertContact(ContactRecord("acc-eve", "eve", "Eve", false, 99))
        repo.upsertChat(ChatRecord("acc-eve", true, 3, true, 5))
        repo.addFolder(FolderRecord("f1", "Family", 0))
        repo.setFolderChats("f1", listOf("acc-bob", "acc-eve"))
        repo.block("acc-mallory", 7)
        repo.set("setting.theme", byteArrayOf(1, 2, 3))
        repo.set("not-a-setting", byteArrayOf(9))

        val exported = rig.ana1.deviceSync.export()
        val fresh = TestDevice(rig.server, "acc-ana", "ana3")
        fresh.deviceSync.import(exported)

        assertEquals(repo.contacts().sortedBy { it.accountId }, fresh.repo.contacts().sortedBy { it.accountId })
        assertEquals(setOf("acc-bob", "acc-eve"), fresh.repo.chats().map { it.id }.toSet())
        assertTrue(fresh.repo.chat("acc-eve")!!.isRequest)
        assertTrue(fresh.repo.chat("acc-eve")!!.muted)
        assertEquals(0, fresh.repo.chat("acc-eve")!!.unread, "no unread counts or messages travel")
        assertTrue(fresh.repo.messages("acc-bob", null, 10).isEmpty())
        assertEquals(listOf("acc-bob", "acc-eve"), fresh.repo.folderChats("f1"))
        assertEquals(listOf("acc-mallory"), fresh.repo.blockedIds())
        assertContentEquals(byteArrayOf(1, 2, 3), fresh.repo.get("setting.theme"))
        assertNull(fresh.repo.get("not-a-setting"))
        // Exporting what was imported gives the same snapshot.
        assertContentEquals(exported, fresh.deviceSync.export())

        // Importing again keeps what is there.
        fresh.repo.setUnread("acc-eve", 2)
        fresh.deviceSync.import(exported)
        assertEquals(2, fresh.repo.chat("acc-eve")!!.unread)
    }

    @Test
    fun invalidSnapshotChangesNothing() = runTest {
        val (rig, _) = joined()
        val before = rig.ana2.repo.contacts()
        assertFailsWith<DeviceSyncException.InvalidSnapshot> { rig.ana2.deviceSync.import("not json".encodeToByteArray()) }
        assertFailsWith<DeviceSyncException.InvalidSnapshot> {
            rig.ana2.deviceSync.import("""{"v":2,"contacts":[],"chats":[],"folders":[],"blocked":[],"settings":{}}""".encodeToByteArray())
        }
        assertEquals(before, rig.ana2.repo.contacts())
        assertNotNull(rig.ana2.repo.chat("acc-bob"))
    }

    // Own-device reconcile safety (mls-profile.md:94, ruling I2)

    private val tenMinutes = 10 * 60_000L

    private suspend fun TestDevice.reconcile() = deviceSync.reconcileOwnDevices()

    @Test
    fun removalNeedsTwoListsTenMinutesApart() = runTest {
        val (rig, record) = joined()
        rig.server.revokeDevice("acc-ana", "ana2")
        rig.ana1.reconcile()
        assertTrue("acc-ana/ana2" in rig.membersOn(rig.ana1, record), "first sighting only records the time")
        rig.ana1.nowMs += tenMinutes - 1
        rig.ana1.reconcile()
        assertTrue("acc-ana/ana2" in rig.membersOn(rig.ana1, record), "not yet ten minutes")
        rig.ana1.nowMs += 1
        rig.ana1.reconcile()
        assertFalse("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
        rig.assertLockDiscipline()
    }

    @Test
    fun missingTimeSurvivesARestart() = runTest {
        val (rig, record) = joined()
        rig.server.revokeDevice("acc-ana", "ana2")
        rig.ana1.reconcile()
        rig.ana1.restart()
        rig.ana1.nowMs += tenMinutes
        rig.ana1.reconcile()
        assertFalse("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
    }

    @Test
    fun deviceThatReappearsInTheListStartsOverAfterwards() = runTest {
        val (rig, record) = joined()
        rig.server.deviceListOverride = { l -> l.filter { it.id != "ana2" } }
        rig.ana1.reconcile()
        rig.server.deviceListOverride = null
        rig.ana1.nowMs += tenMinutes
        rig.ana1.reconcile()
        rig.server.deviceListOverride = { l -> l.filter { it.id != "ana2" } }
        rig.ana1.reconcile()
        assertTrue("acc-ana/ana2" in rig.membersOn(rig.ana1, record), "the earlier sighting was cleared")
    }

    @Test
    fun emptyListRemovesNothing() = runTest {
        val (rig, record) = joined()
        rig.server.deviceListOverride = { emptyList() }
        val commits = rig.server.commitPosts.size
        rig.ana1.reconcile()
        rig.ana1.nowMs += 2 * tenMinutes
        rig.ana1.reconcile()
        assertEquals(commits, rig.server.commitPosts.size)
        assertTrue("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
    }

    @Test
    fun listWithoutThisDeviceRemovesNothing() = runTest {
        val (rig, record) = joined()
        rig.server.deviceListOverride = { l -> l.filter { it.id == "ana2" } }
        val commits = rig.server.commitPosts.size
        rig.ana1.reconcile()
        rig.ana1.nowMs += 2 * tenMinutes
        rig.ana1.reconcile()
        assertEquals(commits, rig.server.commitPosts.size)
        assertTrue("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
        assertTrue("acc-ana/ana1" in rig.membersOn(rig.ana1, record))
    }

    @Test
    fun partialListRemovesOnlyTheDevicesMissingFromTwoLists() = runTest {
        val (rig, record) = joined()
        val ana3 = TestDevice(rig.server, "acc-ana", "ana3", rig.escrowKeys.publicKey)
        ana3.api.uploadKeyPackages(ana3.mls.makeKeyPackages(3))
        rig.ana1.reconcile()
        assertTrue("acc-ana/ana3" in rig.membersOn(rig.ana1, record))
        // A partial answer lacks ana2 and ana3; ana3 shows up again in the second list, ana2 does not.
        rig.server.deviceListOverride = { l -> l.filter { it.id == "ana1" } }
        rig.ana1.reconcile()
        rig.server.deviceListOverride = { l -> l.filter { it.id != "ana2" } }
        rig.ana1.nowMs += tenMinutes
        rig.ana1.reconcile()
        val members = rig.membersOn(rig.ana1, record)
        assertFalse("acc-ana/ana2" in members)
        assertTrue("acc-ana/ana3" in members)
        assertTrue("acc-ana/ana1" in members)
    }

    @Test
    fun deviceLinkedDuringTheReconcileIsNeverRemoved() = runTest {
        val (rig, record) = joined()
        // The list was fetched before ana2 was linked far enough to appear; another device already added it to the group.
        val first = rig.ana1.nowMs
        rig.server.deviceListOverride = { l -> l.filter { it.id != "ana2" } }
        rig.ana1.reconcile()
        // Even if the server then lists it as linked a moment ago while a later pass runs, it stays.
        rig.ana1.nowMs = first + tenMinutes - 1
        rig.ana1.reconcile()
        assertTrue("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
        // A device listed as linked or seen in the last ten minutes is never a removal candidate, whatever else is true.
        rig.server.deviceListOverride = { l ->
            l.filter { it.id != "ana2" } + org.example.app.contracts.DeviceRecord(
                "ana2", "ana2", "android", java.time.Instant.ofEpochMilli(rig.ana1.nowMs - 1000), null, false,
            )
        }
        rig.ana1.nowMs += 5 * tenMinutes
        rig.ana1.reconcile()
        assertTrue("acc-ana/ana2" in rig.membersOn(rig.ana1, record))
    }
}
