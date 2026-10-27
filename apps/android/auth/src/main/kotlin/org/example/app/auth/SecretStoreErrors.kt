package org.example.app.auth

import java.io.IOException

/**
 * The Keystore (or the storage under it) failed in a way that does not prove the key is gone: a busy or
 * restarting keystore daemon, a provider error, an unreadable alias. Nothing was changed or deleted.
 * The caller keeps its state and retries later.
 */
class SecretStoreUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The wrapping key is definitely gone while wrapped secrets still exist: the Keystore reported the alias
 * absent cleanly, or reported the key permanently invalidated. Nothing was deleted. The app layer must run
 * the full wipe (database, `mls/`, `media/`, `secrets/`, alias) and send the person through sign-in again.
 */
class SecretsLost(message: String, cause: Throwable? = null) : Exception(message, cause)
