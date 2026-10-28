package org.example.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore
import org.example.app.contracts.SecretStoreUnavailable

/** Loads fail with `SecretStoreUnavailable` while [failLoads] > 0. */
class BusyLoadStore(private val inner: SecretStore = MemorySecretStore()) : SecretStore by inner {
    var failLoads = 0
    var saves = 0
    override fun load(name: String): ByteArray? {
        if (failLoads > 0) { failLoads--; throw SecretStoreUnavailable("busy") }
        return inner.load(name)
    }
    override fun save(name: String, value: ByteArray) { saves++; inner.save(name, value) }
}

class SessionStoreTest {
    private val s = Session("chat.test", "a1", "d1", "tok")

    private fun seeded(): BusyLoadStore {
        val store = BusyLoadStore()
        SessionStore(store).save(s)
        store.saves = 0
        return store
    }

    @Test
    fun absentWhenNothingIsStored() {
        val st = SessionStore(BusyLoadStore())
        assertEquals(SessionState.Absent, st.state)
        assertNull(st.current)
    }

    @Test
    fun presentWhenStored() {
        assertEquals(SessionState.Present(s), SessionStore(seeded()).state)
    }

    @Test
    fun busyStoreAtLaunchIsUnavailableNotAbsent_andTheNextLoadRestores() {
        val store = seeded().also { it.failLoads = 2 }
        val st = SessionStore(store)
        assertTrue(st.isUnavailable)
        // The retry happens lazily on the next read.
        assertEquals(SessionState.Present(s), st.state)
        assertEquals(s, st.current)
        assertTrue(!st.isUnavailable)
    }

    @Test
    fun stillBusyStaysUnavailable() {
        val st = SessionStore(seeded().also { it.failLoads = 100 })
        assertEquals(SessionState.Unavailable, st.state)
        assertNull(st.current)
        assertEquals(SessionState.Unavailable, st.state)
    }

    @Test
    fun saveNeverOverwritesASessionItCouldNotRead() {
        val store = seeded().also { it.failLoads = 5 }
        val st = SessionStore(store)
        assertFailsWith<SecretStoreUnavailable> { st.save(Session("other", "a2", "d2", "new")) }
        assertEquals(0, store.saves)
        store.failLoads = 0
        assertEquals(s, st.current)
    }

    @Test
    fun saveWorksOnceTheStoreIsReadable() {
        val store = seeded().also { it.failLoads = 1 }
        val st = SessionStore(store)
        val n = Session("chat.test", "a1", "d2", "new")
        st.save(n) // the retry inside save reads the old one, then replaces it
        assertEquals(n, st.current)
        assertEquals(1, store.saves)
    }

    @Test
    fun saveOnAnAbsentStoreIsFine() {
        val store = BusyLoadStore()
        val st = SessionStore(store)
        st.save(s)
        assertEquals(s, st.current)
        assertTrue(store.load(SecretNames.SESSION) != null)
    }
}
