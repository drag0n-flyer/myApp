package org.example.app.contracts

import java.util.concurrent.ConcurrentHashMap

interface SecretStore {
    fun load(name: String): ByteArray?
    fun save(name: String, value: ByteArray)
    fun delete(name: String)
    fun wipeAll()
}

/** Keeps secrets in memory only. For tests and sample data. */
class MemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, ByteArray>()

    override fun load(name: String): ByteArray? = values[name]?.copyOf()
    override fun save(name: String, value: ByteArray) { values[name] = value.copyOf() }
    override fun delete(name: String) { values.remove(name) }
    override fun wipeAll() { values.clear() }
}

object SecretNames {
    const val SESSION = "session"
    const val DB_KEY = "db.key"
    const val MLS_SIGNING = "mls.signing"
}
