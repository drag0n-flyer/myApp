package org.example.app.auth

import android.content.Context
import android.util.Log
import java.io.File
import org.example.app.contracts.SecretStore

/**
 * Secrets wrapped by the Keystore key `app.secrets.v1`, one file each in `noBackupFilesDir/secrets/`
 * (never in backups). See [VaultSecretStore] for the file format and key-loss behaviour.
 */
class KeystoreSecretStore internal constructor(
    private val delegate: VaultSecretStore,
) : SecretStore by delegate {
    constructor(context: Context, dir: File = File(context.noBackupFilesDir, "secrets")) :
        this(VaultSecretStore(KeystoreKey(), dir) { Log.i("KeystoreSecretStore", it) })
}
