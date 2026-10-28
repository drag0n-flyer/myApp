package org.example.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.example.app.auth.VaultSecretStore
import org.example.app.auth.WrappingKeyVault
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.FakeMlsEngine
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.SecretNames
import org.example.app.contracts.ServerEvent
import org.example.app.core.account.AccountState
import org.example.app.core.account.SampleAccountService
import org.example.app.core.chats.SampleChatStore
import org.example.app.live.LivePlatform
import org.example.app.live.SocketHandle
import org.example.app.messaging.LiveChatStore
import org.example.app.net.Session
import org.example.app.net.SessionStore
import org.example.app.net.account.LiveAccountService
import org.example.app.store.AppDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppGraphTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** A software stand-in for the Keystore key (Robolectric has no Keystore). */
    private class SoftVault : WrappingKeyVault {
        var key: SecretKey? = null
        var deletes = 0
        override val backing = "tee"
        override fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key!!) }
        override fun decryptCipher(nonce: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key!!, GCMParameterSpec(128, nonce)) }
        override fun hasKey() = key != null
        override fun createKey() { if (key == null) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun deleteKey() { key = null; deletes++ }
    }

    private class FakeSocket : LiveSocket {
        var running = false
        var starts = 0
        val incoming = kotlinx.coroutines.channels.Channel<ServerEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val events: Flow<ServerEvent> = incoming.receiveAsFlow()
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override suspend fun send(type: String, data: JsonObject): JsonObject? = null
    }

    private val vault = SoftVault()
    private val root = File(context.noBackupFilesDir, "graph-test").apply { deleteRecursively() }
    private val cache = File(context.cacheDir, "graph-test").apply { deleteRecursively() }
    private val socket = FakeSocket()
    private val requests = AtomicInteger(0)

    /** Every request fails at once and is counted: the graph must build without any. */
    private val http = OkHttpClient.Builder().addInterceptor { requests.incrementAndGet(); throw IOException("offline") }.build()

    private fun platform() = LivePlatform(
        filesDir = root,
        cacheDir = cache,
        databaseName = "graph-test.db",
        secrets = { dir -> VaultSecretStore(vault, dir) {} },
        openDatabase = { name, _ -> Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build() },
        likeFallback = true,
        mls = { identity, dir, _ ->
            dir.mkdirs()
            File(dir, "state").writeText("group state")
            FakeMlsEngine(identity)
        },
        hpke = { error("no HPKE in unit tests") },
        http = http,
        socket = { _ -> SocketHandle(socket, start = { socket.running = true; socket.starts++ }, stop = { socket.running = false }) },
        deviceName = "Test phone",
    )

    private fun signedIn(): AppGraph {
        val secrets = VaultSecretStore(vault, File(root, "secrets")) {}
        SessionStore(secrets).save(Session("chat.example.org", "acc-me", "dev-1", "tok"))
        return AppGraph(context, sampleData = false, scope = scope, platform = platform())
    }

    @Test
    fun sampleDataSelectsSampleServices() {
        val graph = AppGraph(context, sampleData = true, scope = scope, platform = platform())
        assertIs<SampleAccountService>(graph.accountService)
        assertIs<SampleChatStore>(graph.chatStore)
        assertEquals(0, requests.get())
        assertFalse(File(root, "secrets").exists())
    }

    @Test
    fun liveGraphBuildsWithoutNetwork() {
        val graph = AppGraph(context, sampleData = false, scope = scope, platform = platform())
        val account = assertIs<LiveAccountService>(graph.accountService)
        assertEquals(AccountState.SignedOut, account.accountState)
        graph.setVisible(true)
        assertEquals(0, requests.get())
        assertEquals(0, socket.starts)
        assertNull(graph.liveSession)
    }

    @Test
    fun revokedWipesEverything() = runBlocking {
        val graph = signedIn()
        assertIs<LiveChatStore>(graph.chatStore)
        val session = graph.liveSession!!
        session.chatStore.settle()
        val media = File(root, "media").apply { mkdirs() }
        File(media, "blob.enc").writeText("x")
        val db = context.getDatabasePath("graph-test.db")
        assertTrue(db.exists())
        assertTrue(File(root, "mls/state").exists())
        assertTrue(File(root, "secrets/${SecretNames.SESSION}").exists())

        graph.wipeAfterRevocation()

        assertFalse(db.exists(), "database")
        assertFalse(File(root, "mls").exists(), "mls/")
        assertFalse(File(root, "media").exists(), "media/")
        assertTrue(File(root, "secrets").listFiles().isNullOrEmpty(), "secrets/")
        assertTrue(vault.deletes > 0, "Keystore alias")
        assertNull(vault.key)
        assertEquals(AccountState.SignedOut, graph.accountService.accountState)
        assertNull(graph.liveSession)
        assertFalse(socket.running)
    }

    @Test
    fun socketOpenOnlyWhileVisibleOrInCall() {
        val graph = signedIn()
        graph.chatStore // opens the signed-in services
        assertFalse(socket.running, "not visible yet")
        graph.setVisible(true)
        assertTrue(socket.running, "visible")
        graph.setVisible(false)
        assertFalse(socket.running, "in the background")
        graph.setInCall(true)
        assertTrue(socket.running, "in a call in the background")
        graph.setVisible(true)
        graph.setInCall(false)
        assertTrue(socket.running, "visible after the call")
        graph.setVisible(false)
        assertFalse(socket.running, "background after the call")
    }

    /** `device.revoked` is only a hint and never wipes; the socket closing with 4003 (Revoked) does. */
    @Test
    fun onlyTheServersProofWipes() = runBlocking {
        val graph = signedIn()
        graph.chatStore
        graph.setVisible(true)
        socket.incoming.send(ServerEvent.DeviceRevoked("dev-1"))
        kotlinx.coroutines.delay(500)
        assertEquals(AccountState.SignedIn, graph.accountService.accountState)
        assertTrue(File(root, "secrets/${SecretNames.SESSION}").exists())

        socket.states.value = ConnectionState.Revoked
        kotlinx.coroutines.withTimeout(10_000) { while (graph.accountService.accountState != AccountState.SignedOut) kotlinx.coroutines.delay(50) }
        kotlinx.coroutines.withTimeout(10_000) { while (vault.key != null) kotlinx.coroutines.delay(50) }
        assertNull(graph.liveSession)
    }
}
