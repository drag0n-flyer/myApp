package org.example.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.app.core.account.AccountState
import org.example.app.core.chats.MessageContent
import org.example.app.live.LivePlatform
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live services against the real Go server on the host (`10.0.2.2`), started by the android-emulator job
 * (`scripts/android-live-server.sh`): two accounts sign up by email code, one adds the other and sends a text, the
 * other receives it. The second account runs as a separate data set in the same process (its own secrets, database
 * and MLS storage), standing in for a second user profile.
 *
 * The app reaches the server only over plain http to its own loopback (debug network config; `ServerAddress` allows
 * plain http for loopback only), so this test forwards `localhost:18080` to the host's server and `localhost:18025`
 * to the host's mail directory. Runs only with the instrumentation argument `liveServer=true`.
 */
@RunWith(AndroidJUnit4::class)
class LiveSmokeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val forwarders = mutableListOf<Forwarder>()
    private val run = System.currentTimeMillis().toString(36).takeLast(6) + (1000..9999).random()

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("needs the host's Go server (liveServer=true)", args.getString("liveServer") == "true")
        forwarders += Forwarder(SERVER_PORT, HOST, args.getString("liveServerPort")?.toInt() ?: 8080)
        forwarders += Forwarder(MAIL_PORT, HOST, args.getString("liveMailPort")?.toInt() ?: 8025)
    }

    @After
    fun tearDown() {
        scope.cancel()
        forwarders.forEach { it.close() }
    }

    @Test
    fun signUpAddContactSendAndReceive() = runBlocking {
        val ana = graph("ana$run")
        val bob = graph("bob$run")
        signUp(ana, "ana_$run")
        signUp(bob, "bob_$run")
        val bobId = bob.chatStore.me.id
        withTimeout(30_000) { while (bob.liveSession!!.api.keyPackageCount() == 0) delay(500) }

        // Bob's KeyPackages reach the server at start-up; Ana adds Bob and writes to him.
        val contact = withTimeout(30_000) {
            var found = ana.chatStore.addContact("bob_$run")
            while (found == null) { delay(500); found = ana.chatStore.addContact("bob_$run") }
            found
        }
        assertEquals(bobId, contact.id)
        val text = "hello from the live smoke test $run"
        val chatId = ana.chatStore.ensureChat(contact.id)
        ana.chatStore.send(MessageContent.Text(text), chatId)

        // Bob receives it as a message request from Ana.
        val anaId = ana.chatStore.me.id
        withTimeout(60_000) {
            while (true) {
                bob.liveSession!!.chatStore.syncInbox()
                val got = (bob.chatStore.requests.value + bob.chatStore.chats.value).any { chat ->
                    bob.chatStore.messages(chat.id, null, 50).any { (it.content as? MessageContent.Text)?.text == text && it.senderId == anaId }
                }
                if (got) break
                delay(1_000)
            }
        }
        Unit
    }

    private fun graph(profile: String): AppGraph {
        val platform = LivePlatform.android(context, profile).let {
            LivePlatform(
                filesDir = it.filesDir.apply { deleteRecursively() }, cacheDir = it.cacheDir.apply { deleteRecursively() },
                databaseName = it.databaseName.also { name -> context.deleteDatabase(name) },
                secrets = it.secrets, openDatabase = it.openDatabase, mls = it.mls, hpke = it.hpke, http = it.http,
                socket = it.socket, deviceName = "Smoke $profile",
            )
        }
        return AppGraph(context, sampleData = false, scope = scope, platform = platform)
    }

    private suspend fun signUp(graph: AppGraph, username: String) {
        val account = graph.accountService
        account.connect("http://localhost:$SERVER_PORT")
        assertTrue(account.redeemInvite(fetch("/invite.txt").trim()))
        val before = newestMail()
        account.startSignUp(username.replaceFirstChar { it.uppercase() }, username, "$username@example.org")
        account.verifyEmail(code(after = before))
        account.finishSignUpWithEmailCode()
        assertEquals(AccountState.SignedIn, account.accountState)
        assertNotNull(graph.openSession())
    }

    // ---- The host's mail directory, served by `python3 -m http.server`

    private fun fetch(path: String): String {
        val c = URL("http://localhost:$MAIL_PORT$path").openConnection() as HttpURLConnection
        return try { c.inputStream.readBytes().decodeToString() } finally { c.disconnect() }
    }

    private fun newestMail(): Int =
        Regex("""href="(\d+)\.eml"""").findAll(fetch("/")).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0

    private suspend fun code(after: Int): String = withTimeout(20_000) {
        while (true) {
            val n = newestMail()
            if (n > after) {
                return@withTimeout Regex("""is (\d{6})\.""").find(fetch("/$n.eml"))?.groupValues?.get(1)
                    ?: error("message $n.eml has no code")
            }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Forwards `127.0.0.1:[localPort]` on the device to [host]:[port] (the emulator's alias for the host's loopback). */
    private class Forwarder(localPort: Int, private val host: String, private val port: Int) {
        private val server = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val inbound = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) {
                        try {
                            val outbound = Socket(host, port)
                            pump(inbound.getInputStream(), outbound.getOutputStream(), outbound)
                            pump(outbound.getInputStream(), inbound.getOutputStream(), inbound)
                        } catch (_: Exception) {
                            inbound.close()
                        }
                    }
                }
            }
        }

        private fun pump(from: InputStream, to: OutputStream, closeWhenDone: Socket) = thread(isDaemon = true) {
            try { from.copyTo(to) } catch (_: Exception) { } finally { try { closeWhenDone.shutdownOutput() } catch (_: Exception) { } }
        }

        fun close() = server.close()
    }

    private companion object {
        const val HOST = "10.0.2.2"
        const val SERVER_PORT = 18080
        const val MAIL_PORT = 18025
    }
}
