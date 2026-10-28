package org.example.app.store

import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore
import java.security.SecureRandom

/** The database key: 32 random bytes kept only in the Keystore-wrapped [SecretStore]. Never log it. */
object DbKey {
    const val SIZE = 32

    fun loadOrCreate(secrets: SecretStore): ByteArray {
        secrets.load(SecretNames.DB_KEY)?.let { existing ->
            // A wrong-sized key means corruption. Making a new key would orphan the database, so stop instead.
            check(existing.size == SIZE) { "Stored database key has an unexpected size" }
            return existing
        }
        val key = ByteArray(SIZE).also { SecureRandom().nextBytes(it) }
        secrets.save(SecretNames.DB_KEY, key)
        return key
    }
}
