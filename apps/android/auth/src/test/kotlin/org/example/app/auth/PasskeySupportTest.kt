package org.example.app.auth

import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.example.app.core.account.AccountError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasskeySupportTest {
    private val cert = ByteArray(32) { (it * 7 + 1).toByte() }
    private val hex = cert.joinToString(":") { "%02X".format(it) }
    private fun links(relation: String = PasskeySupport.RELATION, pkg: String = "com.test.passkeys", fp: String = hex, ns: String = "android_app") = """
        [{"relation":["$relation","delegate_permission/common.handle_all_urls"],
          "target":{"namespace":"$ns","package_name":"$pkg","sha256_cert_fingerprints":["$fp"]}}]
    """.trimIndent()

    private fun ok(json: String?) = PasskeySupport.supported("chat.example.org", json, "com.test.passkeys", cert)

    @Test fun supportedWhenAssetLinksListsUs() {
        assertTrue(ok(links()))
        assertTrue(ok(links(fp = hex.lowercase())))
        assertTrue(ok(links(fp = hex.replace(":", ""))))
    }

    @Test fun unsupportedForOtherCert() {
        val other = cert.copyOf().also { it[0] = (it[0] + 1).toByte() }.joinToString(":") { "%02X".format(it) }
        assertFalse(ok(links(fp = other)))
        assertFalse(ok(links(pkg = "org.other.app")))
        assertFalse(ok(links(relation = "delegate_permission/common.handle_all_urls")))
        assertFalse(ok(links(ns = "web")))
        assertFalse(PasskeySupport.supported("chat.example.org", links(), "com.test.passkeys", ByteArray(5)))
    }

    @Test fun unsupportedWhenFileMissing() {
        assertFalse(ok(null))
        assertFalse(ok(""))
        assertFalse(ok("not json"))
        assertFalse(ok("{}"))
        assertFalse(PasskeySupport.supported("", links(), "com.test.passkeys", cert))
    }

    private class Fake(
        val onCreate: () -> CreateCredentialResponse = { error("unscripted") },
        val onGet: () -> GetCredentialResponse = { error("unscripted") },
    ) : CredentialApi {
        val createOptions = mutableListOf<String>()
        val getOptions = mutableListOf<String>()
        override suspend fun create(optionsJson: String): CreateCredentialResponse { createOptions += optionsJson; return onCreate() }
        override suspend fun get(optionsJson: String): GetCredentialResponse { getOptions += optionsJson; return onGet() }
    }

    private fun failing(e: Exception) = CredentialManagerPasskeys(Fake({ throw e }, { throw e }))

    private suspend fun unavailable(block: suspend () -> Unit) =
        assertEquals(AccountError.PasskeyUnavailable, assertFailsWith<AccountError> { block() })
    private suspend fun temporary(block: suspend () -> Unit) =
        assertEquals(AccountError.PasskeyTemporary, assertFailsWith<AccountError> { block() })

    @Test fun phonesThatCannotMakePasskeysAreUnavailable() = runTest {
        unavailable { failing(CreateCredentialNoCreateOptionException("x")).create("{}") }
        unavailable { failing(CreateCredentialUnsupportedException("x")).create("{}") }
        unavailable { failing(CreateCredentialProviderConfigurationException("x")).create("{}") }
        unavailable { failing(GetCredentialUnsupportedException("x")).get("{}") }
        unavailable { failing(GetCredentialProviderConfigurationException("x")).get("{}") }
        unavailable { failing(NoCredentialException("x")).get("{}") }
    }

    @Test fun otherFailuresAreTemporaryAndNeverUnavailable() = runTest {
        temporary { failing(IllegalStateException("x")).get("{}") }
        temporary { failing(IllegalStateException("x")).create("{}") }
        temporary { failing(CreateCredentialUnknownException("x")).create("{}") }
        temporary { failing(GetCredentialUnknownException("x")).get("{}") }
    }

    @Test fun userCancelAndInterruptionMapToCancelled() = runTest {
        for (e in listOf(CreateCredentialCancellationException("x"), CreateCredentialInterruptedException("x"))) {
            assertEquals(AccountError.Cancelled, assertFailsWith<AccountError> { failing(e).create("{}") })
        }
        for (e in listOf(GetCredentialCancellationException("x"), GetCredentialInterruptedException("x"))) {
            assertEquals(AccountError.Cancelled, assertFailsWith<AccountError> { failing(e).get("{}") })
        }
    }

    @Test fun coroutineCancellationPropagates() = runTest {
        assertFailsWith<CancellationException> { failing(CancellationException("x")).get("{}") }
    }

    @Test fun errorMessagesNeverCarryTheOptions() = runTest {
        val e = assertFailsWith<AccountError> { failing(IllegalStateException("secret-challenge")).create("{\"challenge\":\"secret-challenge\"}") }
        assertFalse((e.message ?: "").contains("secret-challenge"))
        assertEquals(null, e.cause)
    }

    // Deliberately odd spacing, key order and escapes: any parse and re-encode would change it.
    private val options = "{ \"challenge\" : \"YWJj\",\n  \"rp\":{\"id\":\"chat.example.org\"},\"x\":\"\\u00e9\" }"

    @Test fun createPassesOptionsThroughByteForByteAndReturnsTheResponseUnchanged() = runTest {
        val response = "{ \"id\":\"AAA\", \"response\":{\"attestationObject\":\"QQ\"} }"
        val fake = Fake(onCreate = { CreatePublicKeyCredentialResponse(response) })
        assertEquals(response, CredentialManagerPasskeys(fake).create(options))
        assertEquals(listOf(options), fake.createOptions)
    }

    @Test fun getPassesOptionsThroughByteForByteAndReturnsTheResponseUnchanged() = runTest {
        val response = "{ \"id\":\"AAA\", \"response\":{\"signature\":\"QQ\"} }"
        val fake = Fake(onGet = { GetCredentialResponse(PublicKeyCredential(response)) })
        assertEquals(response, CredentialManagerPasskeys(fake).get(options))
        assertEquals(listOf(options), fake.getOptions)
    }

    @Test fun aWrongResponseTypeIsRejected() = runTest {
        unavailable { CredentialManagerPasskeys(Fake(onCreate = { CreatePasswordResponse() })).create(options) }
        unavailable { CredentialManagerPasskeys(Fake(onGet = { GetCredentialResponse(PasswordCredential("u", "p")) })).get(options) }
    }
}
