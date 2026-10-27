package org.example.app.contracts


/**
 * The Keystore (or the storage under it) failed in a way that does not prove the key is gone: a busy or
 * restarting keystore daemon, a provider error, an unreadable alias, a cipher call that failed inside the
 * keystore. Nothing was changed or deleted. Never means "tampered" and never means "no secret".
 *
 * App-layer rules (see also [SecretsLost] and [SecretStore.load]):
 * 1. Transient everywhere: keep all state, do not sign out, do not create keys, retry with backoff.
 *    A push wake gives up quietly and leaves the message for the next wake; the UI shows "can't open, try again".
 * 4. Never catch this and fall back to "create a new key/secret" (`DbKey.loadOrCreate`, `SigningKeys.loadOrCreate`).
 * 5. Catch it by type (`SessionStore`, `ErrorMapping`); do not map it to "signed out" or a generic internal error.
 */
class SecretStoreUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The wrapping key is definitely gone while wrapped secrets still exist: the Keystore reported the alias
 * absent cleanly, or reported the key permanently invalidated. Nothing was deleted.
 *
 * App-layer rules:
 * 2. Run the full wipe (database, `mls/`, `media/`, `secrets/` via [SecretStore.wipeAll], alias), then sign-in.
 *    Do not wipe on the first signal: keystore2 `containsAlias` returns false for any error other than
 *    "key not found", so confirm (a second check after a delay, or at the next process start). Never wipe
 *    from a background push wake.
 * 4. Never swallow it and fall back to "create".
 * 5. Catch it by type; do not turn it into a generic error.
 * 6. Use only `KeystoreSecretStore(context)` with the default directory: a second directory shares the same
 *    alias, and its `wipeAll` would delete that alias.
 */
class SecretsLost(message: String, cause: Throwable? = null) : Exception(message, cause)
