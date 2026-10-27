package org.example.app.auth

import androidx.credentials.CreateCredentialResponse
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

class PasskeySupportTest {
    private val cert = ByteArray(32) { (it * 7 + 1).toByte() }
    private val hex = cert.joinToString(":") { "%02X".format(it) }
    private fun links(relation: String = PasskeySupport.RELATION, pkg: String = "org.example.app", fp: String = hex, ns: String = "android_app") = """
        [{"relation":["$relation","delegate_permission/common.handle_all_urls"],
          "target":{"namespace":"$ns","package_name":"$pkg","sha256_cert_fingerprints":["$fp"]}}]
    """.trimIndent()

    private fun ok(json: String?) = PasskeySupport.supported("chat.example.org", json, "org.example.app", cert)

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
        assertFalse(PasskeySupport.supported("chat.example.org", links(), "org.example.app", ByteArray(5)))
    }

    @Test fun unsupportedWhenFileMissing() {
        assertFalse(ok(null))
        assertFalse(ok(""))
        assertFalse(ok("not json"))
        assertFalse(ok("{}"))
        assertFalse(PasskeySupport.supported("", links(), "org.example.app", cert))
    }

    private class Fake(val onCreate: () -> CreateCredentialResponse, val onGet: () -> GetCredentialResponse) : CredentialApi {
        override suspend fun create(optionsJson: String) = onCreate()
        override suspend fun get(optionsJson: String) = onGet()
    }

    private fun failing(e: Exception) = CredentialManagerPasskeys(Fake({ throw e }, { throw e }))

    @Test fun noCreateOptionMapsToUnavailable() = runTest {
        assertEquals(AccountError.PasskeyUnavailable, assertFailsWith<AccountError> { failing(CreateCredentialNoCreateOptionException("x")).create("{}") })
        assertEquals(AccountError.PasskeyUnavailable, assertFailsWith<AccountError> { failing(CreateCredentialUnsupportedException("x")).create("{}") })
        assertEquals(AccountError.PasskeyUnavailable, assertFailsWith<AccountError> { failing(NoCredentialException("x")).get("{}") })
        assertEquals(AccountError.PasskeyUnavailable, assertFailsWith<AccountError> { failing(IllegalStateException("x")).get("{}") })
    }

    @Test fun userCancelMapsToCancelled() = runTest {
        assertEquals(AccountError.Cancelled, assertFailsWith<AccountError> { failing(CreateCredentialCancellationException("x")).create("{}") })
        assertEquals(AccountError.Cancelled, assertFailsWith<AccountError> { failing(GetCredentialCancellationException("x")).get("{}") })
    }

    @Test fun coroutineCancellationPropagates() = runTest {
        assertFailsWith<CancellationException> { failing(CancellationException("x")).get("{}") }
    }

    @Test fun errorMessagesNeverCarryTheOptions() = runTest {
        val e = assertFailsWith<AccountError> { failing(IllegalStateException("secret-challenge")).create("{\"challenge\":\"secret-challenge\"}") }
        assertFalse((e.message ?: "").contains("secret-challenge"))
        assertEquals(null, e.cause)
    }
}
