package org.example.app.net.account

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.contracts.ServerException
import org.example.app.core.account.AccountError

class AccountErrorsTest {
    private fun err(code: String) = ServerException(400, code, null, null)

    @Test fun signUpMapsGoneCodesToSignupExpired() {
        for (code in listOf("not_found", "forbidden", "expired")) {
            assertEquals(AccountError.SignupExpired, AccountErrors.map(err(code), signup = true), code)
        }
    }

    @Test fun signUpKeepsOtherCodes() {
        assertEquals(AccountError.CodeInvalid, AccountErrors.map(err("code_invalid"), signup = true))
        assertEquals(AccountError.Network, AccountErrors.map(err("network"), signup = true))
        assertEquals(AccountError.Server, AccountErrors.map(err("boom"), signup = true))
    }

    @Test fun otherCallsMapAsBefore() {
        assertEquals(AccountError.Server, AccountErrors.map(err("not_found")))
        assertEquals(AccountError.Server, AccountErrors.map(err("forbidden")))
        assertEquals(AccountError.CodeExpired, AccountErrors.map(err("expired")))
        assertEquals(AccountError.LinkExpired, AccountErrors.map(err("expired"), link = true))
        assertEquals(AccountError.LinkExpired, AccountErrors.map(err("not_found"), link = true))
    }
}
