package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/** A redeemed one-time link names the person: no username lookup is needed (or made). */
class AddKnownContactTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    /** Every lookup answers "no one": a contact added through it would be a bug. */
    private class NoLookup : SocialPort {
        var lookups = 0
        override suspend fun lookUpUser(username: String): FoundUser? { lookups++; return null }
        override suspend fun block(accountId: String) = Unit
        override suspend fun unblock(accountId: String) = Unit
        override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
    }

    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private fun store(d: TestDevice, social: SocialPort) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { true }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), social = social, scope = scope,
    )

    @Test fun addsTheNamedPersonWithoutALookup() = runTest {
        val social = NoLookup()
        val st = store(Rig().start().bob1, social)
        val who = st.addKnownContact("acc-cy", "Cy", "Cy Young")
        assertEquals("acc-cy", who?.id)
        assertEquals("cy", who?.username)
        assertEquals("Cy Young", who?.displayName)
        assertEquals(listOf("acc-cy"), st.contacts.value.map { it.id })
        assertEquals(0, social.lookups)
    }

    @Test fun aBlankAccountOrUsernameAddsNobody() = runTest {
        val st = store(Rig().start().bob1, NoLookup())
        assertNull(st.addKnownContact("", "cy", "Cy"))
        assertNull(st.addKnownContact("acc-cy", "  ", "Cy"))
        assertEquals(emptyList(), st.contacts.value)
    }
}
