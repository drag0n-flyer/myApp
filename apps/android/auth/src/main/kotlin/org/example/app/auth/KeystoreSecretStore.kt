package org.example.app.auth

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.example.app.contracts.SecretStore

/**
 * Secrets wrapped by the Keystore key `app.secrets.v1`, one file each in `noBackupFilesDir/secrets/`
 * (never in backups). See [VaultSecretStore] for the file format and the error behaviour.
 *
 * Every instance for the same directory is a thin handle on one process-wide [VaultSecretStore], so the
 * lock, the cache and the one-time key creation are shared however many places construct this class.
 */
class KeystoreSecretStore internal constructor(
    private val delegate: VaultSecretStore,
) : SecretStore by delegate {
    constructor(context: Context, dir: File = File(context.noBackupFilesDir, "secrets")) :
        this(StoreRegistry.obtain(dir) { VaultSecretStore(SharedKey.instance, dir) { Log.i("KeystoreSecretStore", it) } })

    private object SharedKey {
        val instance by lazy { KeystoreKey() }
    }
}

/** One [VaultSecretStore] per directory for the whole process. */
internal object StoreRegistry {
    private val stores = ConcurrentHashMap<String, VaultSecretStore>()

    fun obtain(dir: File, create: () -> VaultSecretStore): VaultSecretStore =
        stores.computeIfAbsent(dir.canonicalPath) { create() }
}
