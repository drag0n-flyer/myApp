package org.example.app.live

import android.content.Context
import java.io.File
import okhttp3.OkHttpClient
import org.example.app.auth.KeystoreSecretStore
import org.example.app.contracts.Hpke
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.MlsEngine
import org.example.app.contracts.SecretStore
import org.example.app.mls.LiveMlsEngine
import org.example.app.mls.RustHpke
import org.example.app.net.ServerClient
import org.example.app.net.live.LiveConnection
import org.example.app.net.live.SystemLiveClock
import org.example.app.store.AppDatabase

/** Mirrors the iPhone's `AppConfig.defaultServerHost` fallback until the owner names a server. */
const val DEFAULT_SERVER_HOST = "home.example.org"

/** The live socket and how to open and close it. */
class SocketHandle(val socket: LiveSocket, val start: () -> Unit, val stop: () -> Unit)

/**
 * Where the live services keep their data and how the platform parts are made. [android] is the real one; unit
 * tests swap the Keystore, SQLCipher, the Rust engine and the socket, which Robolectric cannot run.
 *
 * Layout under [filesDir] (`noBackupFilesDir`, never in a backup): `secrets/` (Keystore-wrapped secrets), `mls/`
 * (the MLS storage), `media/` (encrypted attachments). The database is [databaseName] in the app's database
 * directory; [cacheDir] holds decrypted files in view and outgoing ciphertext.
 */
class LivePlatform(
    val filesDir: File,
    val cacheDir: File,
    val databaseName: String = AppDatabase.FILE_NAME,
    val secrets: (dir: File) -> SecretStore,
    val openDatabase: (name: String, key: ByteArray) -> AppDatabase,
    /** Only the JVM tests set it: Robolectric's SQLite has no FTS5. */
    val likeFallback: Boolean = false,
    val mls: (identity: String, dir: File, secrets: SecretStore) -> MlsEngine,
    val hpke: () -> Hpke,
    val http: OkHttpClient,
    val socket: (ServerClient) -> SocketHandle,
    val deviceName: String,
    val defaultHost: String = DEFAULT_SERVER_HOST,
) {
    val secretsDir: File get() = File(filesDir, "secrets")
    val mlsDir: File get() = File(filesDir, "mls")
    val mediaDir: File get() = File(filesDir, "media")

    companion object {
        /**
         * The real platform. [profile] keeps a separate data set under `profiles/<profile>/` and `<profile>.db`; only
         * `LiveSmokeTest` uses it, to run two accounts in one process.
         */
        fun android(context: Context, profile: String? = null): LivePlatform {
            val app = context.applicationContext
            val http = OkHttpClient()
            return LivePlatform(
                filesDir = profile?.let { File(app.noBackupFilesDir, "profiles/$it") } ?: app.noBackupFilesDir,
                cacheDir = profile?.let { File(app.cacheDir, "profiles/$it") } ?: app.cacheDir,
                databaseName = profile?.let { "$it.db" } ?: AppDatabase.FILE_NAME,
                secrets = { dir -> KeystoreSecretStore(app, dir) },
                openDatabase = { name, key -> AppDatabase.open(app, key, name) },
                mls = { identity, dir, secrets -> LiveMlsEngine(identity, dir, secrets) },
                hpke = { RustHpke() },
                http = http,
                socket = { client ->
                    val connection = LiveConnection(client, http, SystemLiveClock, kotlin.random.Random.Default)
                    SocketHandle(connection, connection::start, connection::stop)
                },
                deviceName = android.os.Build.MODEL ?: "Android",
            )
        }
    }
}
