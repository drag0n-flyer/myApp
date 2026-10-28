package org.example.app.contracts

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse

class SecretStoreErrorsTest {
    // A caller that treats IOException as "corrupt item" must never catch a transient Keystore failure.
    @Test
    fun unavailableIsNotAnIOException() {
        assertFalse(SecretStoreUnavailable("x") as Throwable is IOException)
    }
}
