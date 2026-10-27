package org.example.app.store

import org.example.app.contracts.MemorySecretStore
import org.example.app.contracts.SecretNames
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class DbKeyTest {
    @Test fun createsThirtyTwoBytesAndStoresUnderDbKey() {
        val secrets = MemorySecretStore()
        val key = DbKey.loadOrCreate(secrets)
        assertEquals(32, key.size)
        assertNotNull(secrets.load("db.key"))
        assertContentEquals(key, secrets.load(SecretNames.DB_KEY))
    }

    @Test fun reloadsTheSameKey() {
        val secrets = MemorySecretStore()
        assertContentEquals(DbKey.loadOrCreate(secrets), DbKey.loadOrCreate(secrets))
    }

    @Test fun differentStoresGetDifferentKeys() {
        assertNotEquals(DbKey.loadOrCreate(MemorySecretStore()).toList(), DbKey.loadOrCreate(MemorySecretStore()).toList())
    }

    @Test fun refusesAStoredKeyOfTheWrongSize() {
        val secrets = MemorySecretStore().apply { save("db.key", ByteArray(5)) }
        assertFailsWith<IllegalStateException> { DbKey.loadOrCreate(secrets) }
    }
}
