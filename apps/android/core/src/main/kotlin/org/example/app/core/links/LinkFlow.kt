package org.example.app.core.links

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The life of a followed link: kept as the raw URL while the app is locked or signed out (one, the latest), parsed and
 * opened only when handled, against the server the person is signed in to then. One link at a time: none starts while
 * one is being opened or while its outcome has not been acted on (a redeemed token must not be lost). An outcome from
 * an account that has since ended (see [generation]) is discarded.
 */
class LinkFlow(
    private val opener: ContactLinkOpener,
    /** Changes when the account ends (sign-out, wipe). */
    private val generation: () -> Int,
    /** Runs the block outside the screens, so leaving one does not cancel a redeem. */
    private val launch: (suspend () -> Unit) -> Unit,
) {
    val pending = PendingLink()
    private val _outcome = MutableStateFlow<LinkOutcome?>(null)
    /** What the last link led to, until [takeOutcome]. */
    val outcome: StateFlow<LinkOutcome?> get() = _outcome
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy

    /** Starts the waiting link when [ready] (unlocked and signed in) and nothing else is open or waiting. */
    fun start(ready: Boolean) {
        if (!ready || _busy.value || _outcome.value != null) return
        val url = pending.take(true) ?: return
        val born = generation()
        _busy.value = true
        launch {
            try {
                val result = opener.open(url)
                if (generation() == born) _outcome.value = result
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * A problem or an open-chat outcome, once, when [ready]; while locked it stays. A confirmation is not taken here: it
     * stays (so a rotation or the lock cannot lose a redeemed person) until [finishConfirmation].
     */
    fun takeOutcome(ready: Boolean): LinkOutcome? {
        if (!ready) return null
        val o = _outcome.value ?: return null
        if (o is LinkOutcome.Confirm) return null
        _outcome.value = null
        return o
    }

    /** The confirmation screen reports Add or Cancel. */
    fun finishConfirmation() {
        if (_outcome.value is LinkOutcome.Confirm) _outcome.value = null
    }

    /** The account ended: an outcome of the old account is dropped. A link still waiting stays for the next sign-in. */
    fun accountEnded() { _outcome.value = null }
}
