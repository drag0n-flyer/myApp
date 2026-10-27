package org.example.app.auth

import android.content.Context
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import kotlinx.coroutines.CancellationException
import org.example.app.core.account.AccountError

/**
 * Passkeys. Both calls take the server's bare WebAuthn options JSON unchanged and return the credential
 * JSON the server's `finish` call expects. They throw only [AccountError.PasskeyUnavailable] (no provider,
 * no screen lock, unsupported, no matching passkey, or any other provider failure: the app falls back to an
 * email code) or [AccountError.Cancelled] (the person closed the sheet: not an error), plus coroutine
 * cancellation. Options, credentials and challenges are never logged and never put in exception messages.
 */
interface Passkeys {
    suspend fun create(optionsJson: String): String
    suspend fun get(optionsJson: String): String
}

/** The two raw Credential Manager calls; faked on the JVM. */
interface CredentialApi {
    suspend fun create(optionsJson: String): CreateCredentialResponse
    suspend fun get(optionsJson: String): androidx.credentials.GetCredentialResponse
}

/** Real calls. [context] must be the current Activity (it hosts the system sheet), read at call time. */
class CredentialManagerApi(private val context: () -> Context?) : CredentialApi {
    private fun manager(): CredentialManager =
        CredentialManager.create(context() ?: throw AccountError.PasskeyUnavailable)

    override suspend fun create(optionsJson: String): CreateCredentialResponse {
        val ctx = context() ?: throw AccountError.PasskeyUnavailable
        return manager().createCredential(ctx, CreatePublicKeyCredentialRequest(optionsJson))
    }

    override suspend fun get(optionsJson: String): androidx.credentials.GetCredentialResponse {
        val ctx = context() ?: throw AccountError.PasskeyUnavailable
        return manager().getCredential(ctx, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(optionsJson))))
    }
}

class CredentialManagerPasskeys(private val api: CredentialApi) : Passkeys {
    constructor(activity: () -> Context?) : this(CredentialManagerApi(activity))

    override suspend fun create(optionsJson: String): String = try {
        (api.create(optionsJson) as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw AccountError.PasskeyUnavailable
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw mapPasskeyFailure(e)
    }

    override suspend fun get(optionsJson: String): String = try {
        (api.get(optionsJson).credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw AccountError.PasskeyUnavailable
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw mapPasskeyFailure(e)
    }
}

/** Maps a Credential Manager failure; a cancel or interruption is [AccountError.Cancelled], everything else unavailable. */
internal fun mapPasskeyFailure(e: Throwable): AccountError = when (e) {
    is AccountError -> e
    is CreateCredentialCancellationException, is GetCredentialCancellationException,
    is CreateCredentialInterruptedException, is GetCredentialInterruptedException -> AccountError.Cancelled
    // NoCreateOption, Unsupported, ProviderConfiguration, NoCredential, DOM and custom errors, anything unknown.
    is CreateCredentialException, is GetCredentialException -> AccountError.PasskeyUnavailable
    else -> AccountError.PasskeyUnavailable
}
