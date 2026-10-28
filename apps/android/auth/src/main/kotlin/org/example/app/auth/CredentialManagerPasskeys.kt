package org.example.app.auth

import android.app.Activity
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import kotlinx.coroutines.CancellationException
import org.example.app.core.account.AccountError

/**
 * Passkeys. Both calls take the server's bare WebAuthn options JSON unchanged and return the credential
 * JSON the server's `finish` call expects. They throw only [AccountError.PasskeyUnavailable] (this phone cannot
 * do passkeys: no provider, unsupported, no matching passkey; the app may offer an email code),
 * [AccountError.PasskeyTemporary] (a passing failure: say so and let the person retry, never switch to the email
 * code automatically) or [AccountError.Cancelled] (the person closed the sheet: not an error), plus coroutine
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

/** Real calls. [activity] is the current Activity (it hosts the system sheet), read once per call; null means none is showing. */
class CredentialManagerApi(private val activity: () -> Activity?) : CredentialApi {
    override suspend fun create(optionsJson: String): CreateCredentialResponse {
        val ctx = activity() ?: throw AccountError.PasskeyTemporary
        return CredentialManager.create(ctx).createCredential(ctx, CreatePublicKeyCredentialRequest(optionsJson))
    }

    override suspend fun get(optionsJson: String): androidx.credentials.GetCredentialResponse {
        val ctx = activity() ?: throw AccountError.PasskeyTemporary
        return CredentialManager.create(ctx).getCredential(ctx, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(optionsJson))))
    }
}

class CredentialManagerPasskeys(private val api: CredentialApi) : Passkeys {
    constructor(activity: () -> Activity?) : this(CredentialManagerApi(activity))

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

/**
 * Maps a Credential Manager failure, never keeping its message or cause. A cancel or interruption is
 * [AccountError.Cancelled]. "This phone cannot do passkeys" (no provider, unsupported, no matching passkey) is
 * [AccountError.PasskeyUnavailable]; everything else is [AccountError.PasskeyTemporary].
 */
internal fun mapPasskeyFailure(e: Throwable): AccountError = when (e) {
    is AccountError -> e
    is CreateCredentialCancellationException, is GetCredentialCancellationException,
    is CreateCredentialInterruptedException, is GetCredentialInterruptedException -> AccountError.Cancelled
    is CreateCredentialNoCreateOptionException, is CreateCredentialUnsupportedException,
    is CreateCredentialProviderConfigurationException, is GetCredentialUnsupportedException,
    is GetCredentialProviderConfigurationException, is NoCredentialException -> AccountError.PasskeyUnavailable
    // Unknown, DOM and custom provider errors, and anything else.
    else -> AccountError.PasskeyTemporary
}
