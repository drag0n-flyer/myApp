package org.example.app.push

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.Hpke
import org.example.app.contracts.HpkeException
import org.example.app.contracts.HpkeInfo
import org.example.app.contracts.X25519KeyPair
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Stand-in for HPKE: "sealing" prefixes the recipient key, so only the matching private key opens it. */
private class FakeHpke : Hpke {
    var lastInfo: String? = null

    override fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray): ByteArray {
        lastInfo = info
        return recipientPublicKey + plaintext
    }

    override fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray): ByteArray {
        if (info != lastInfo || !sealed.copyOfRange(0, 32).contentEquals(keyPair.publicKey)) throw HpkeException()
        return sealed.copyOfRange(32, sealed.size)
    }

    override fun generateKeyPair() = X25519KeyPair(ByteArray(32) { 1 }, ByteArray(32) { 2 })
}

private class FakeApi(var gateway: String? = "https://push.example.net") : PushApi {
    val calls = mutableListOf<String>()
    var sealed: ByteArray? = null
    var upEndpoint: String? = null
    var upP256dh: ByteArray? = null
    var upAuth: ByteArray? = null

    override suspend fun serverGatewayUrl() = gateway
    override suspend fun serverVapidKey() = "vapid"
    override suspend fun putFcm(sealed: ByteArray) { calls += "put:fcm"; this.sealed = sealed }
    override suspend fun putUnifiedPush(endpoint: String, p256dh: ByteArray, auth: ByteArray) {
        calls += "put:unifiedpush"; upEndpoint = endpoint; upP256dh = p256dh; upAuth = auth
    }
    override suspend fun delete(kind: String) { calls += "delete:$kind" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // the newest SDK this Robolectric release runs on JDK 17
class PushTest {
    private val hpke = FakeHpke()
    private val keys = hpke.generateKeyPair()
    private val appId = "test.app.id"
    private val url = "https://push.example.net"

    private fun registrar(api: PushApi) = PushRegistrar(api, hpke, keys.publicKey, url, appId)

    @Test
    fun fcmHandleSealedWithGatewayKey() = runTest {
        val api = FakeApi()
        assertTrue(registrar(api).register(PushTransport.Fcm, PushRegistration.Fcm("tok-1")))
        val opened = hpke.open(keys, HpkeInfo.PUSH_HANDLE, api.sealed!!)
        assertEquals("""{"t":"tok-1","k":"fcm","e":"production","b":"test.app.id"}""", opened.decodeToString())
    }

    @Test
    fun noTokensWhenServerNamesOtherGateway() = runTest {
        val other = FakeApi(gateway = "https://evil.example.org")
        assertFalse(registrar(other).register(PushTransport.Fcm, PushRegistration.Fcm("tok")))
        assertTrue(other.calls.isEmpty())
        val none = FakeApi(gateway = null)
        assertFalse(registrar(none).register(PushTransport.Fcm, PushRegistration.Fcm("tok")))
        assertTrue(none.calls.isEmpty())
        val same = FakeApi(gateway = "HTTPS://Push.Example.net:443/")
        assertTrue(registrar(same).register(PushTransport.Fcm, PushRegistration.Fcm("tok")))
    }

    @Test
    fun switchingDeletesOtherKind() = runTest {
        val api = FakeApi()
        registrar(api).register(PushTransport.Fcm, PushRegistration.Fcm("tok"))
        assertEquals(listOf("put:fcm", "delete:unifiedpush"), api.calls)
        api.calls.clear()
        registrar(api).register(PushTransport.UnifiedPush, PushRegistration.UnifiedPush("https://up.example.net/x", ByteArray(65), ByteArray(16)))
        assertEquals(listOf("put:unifiedpush", "delete:fcm"), api.calls)
    }

    @Test
    fun unifiedPushUploadsEndpointAndKeys() = runTest {
        val api = FakeApi()
        val p256dh = ByteArray(65) { it.toByte() }
        val auth = ByteArray(16) { (it + 7).toByte() }
        assertTrue(registrar(api).register(PushTransport.UnifiedPush, PushRegistration.UnifiedPush("https://up.example.net/abc", p256dh, auth)))
        assertEquals("https://up.example.net/abc", api.upEndpoint)
        assertContentEquals(p256dh, api.upP256dh)
        assertContentEquals(auth, api.upAuth)
    }

    @Test
    fun noTransportRegistersNothing() = runTest {
        val api = FakeApi()
        assertFalse(registrar(api).register(PushTransport.None, null))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun chooseFcmWhenPlayServices() {
        assertEquals(PushTransport.Fcm, PushTransport.choose(playServices = true, distributorCount = 2))
        // Play services without a Firebase configuration falls back to a distributor.
        assertEquals(PushTransport.UnifiedPush, PushTransport.choose(playServices = true, distributorCount = 1, fcmConfigured = false))
    }

    @Test
    fun chooseUnifiedPushWhenNoPlayAndOneDistributor() {
        assertEquals(PushTransport.UnifiedPush, PushTransport.choose(playServices = false, distributorCount = 1))
        assertEquals(PushTransport.UnifiedPush, PushTransport.choose(playServices = false, distributorCount = 3, distributorChosen = true))
        assertEquals(PushTransport.None, PushTransport.choose(playServices = false, distributorCount = 3))
    }

    @Test
    fun noneWhenNeither() {
        assertEquals(PushTransport.None, PushTransport.choose(playServices = false, distributorCount = 0))
    }

    @Test
    fun playServicesDetectedFromPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(PlayServices.available(context))
        val info = ApplicationInfo().apply { packageName = "com.google.android.gms"; enabled = true }
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.google.android.gms"; applicationInfo = info
        })
        assertTrue(PlayServices.available(context))
    }

    @Test
    fun firebaseInitToleratesMissingConfig() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(FirebaseInit.init(context, FirebaseInit.Options("", "", "", "")))
        assertFalse(FirebaseInit.init(context))
    }

    @Test
    fun gatewayConfigNotHardCodedAppId() {
        assertTrue(BuildConfig.APP_ID.isNotEmpty())
    }
}
