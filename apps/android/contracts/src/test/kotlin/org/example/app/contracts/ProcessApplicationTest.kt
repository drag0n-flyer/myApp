package org.example.app.contracts

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ProcessApplicationTest {
    @Test
    fun acceptsOnlyApplicationMessagesAndChangesNothingOtherwise() = runTest {
        val ana = FakeMlsEngine("a/1"); val bob = FakeMlsEngine("b/1"); val cy = FakeMlsEngine("c/1")
        val g = ana.createGroup()
        val add = ana.add(bob.makeKeyPackages(1), g)
        ana.commitAccepted(g)
        assertFailsWith<MlsException.Malformed> { bob.processApplication(add.welcome!!) }
        assertFailsWith<MlsException.UnknownGroup> { bob.epoch(g) }
        bob.process(add.welcome!!)
        val second = ana.add(cy.makeKeyPackages(1), g)
        ana.commitAccepted(g)
        assertFailsWith<MlsException.Malformed> { bob.processApplication(second.commit) }
        assertEquals(1L, bob.epoch(g))
        bob.process(second.commit)
        assertEquals(2L, bob.epoch(g))
        val app = bob.processApplication(ana.encrypt("hi".encodeToByteArray(), g).ciphertext)
        assertContentEquals("hi".encodeToByteArray(), app.plaintext)
        assertEquals("a/1", app.sender)
    }
}
