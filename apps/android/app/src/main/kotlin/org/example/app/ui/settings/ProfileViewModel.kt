package org.example.app.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.account.AccountError
import org.example.app.core.profile.DisplayNameRule

enum class ProfileAction { SigningOut, Deleting, SavingName }

data class ProfileState(
    /** The action running now; both rows are off while it runs. */
    val busy: ProfileAction? = null,
    @StringRes val error: Int? = null,
    /** The "Edit name" dialog is open. */
    val editingName: Boolean = false,
)

/**
 * The one place that runs sign-out and account deletion, owned by the graph and run in the graph's [scope]: leaving
 * the screen or a rotation neither stops an action nor lets a second one start beside it. [running] says which one is
 * going; a failure is kept in [error] (a string resource) until a screen takes it, so one that happens after the person
 * left is still shown the next time the profile screen opens.
 */
class AccountActions(
    private val scope: CoroutineScope,
    private val signOut: suspend () -> Unit,
    private val deleteAccount: suspend () -> Unit,
    private val saveName: suspend (String) -> Unit = {},
) {
    private val _running = MutableStateFlow<ProfileAction?>(null)
    val running: StateFlow<ProfileAction?> = _running.asStateFlow()

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    /** Starts [action] unless one is running (then it does nothing). */
    fun start(action: ProfileAction) {
        launch(action, { if (it is AccountError.Network) R.string.status_offline else R.string.err_server }) {
            if (action == ProfileAction.SigningOut) signOut() else deleteAccount()
        }
    }

    /** Saves the display name unless an action is running (then it does nothing). Any failure is [R.string.profile_name_error]. */
    fun saveName(name: String) {
        launch(ProfileAction.SavingName, { R.string.profile_name_error }) { saveName.invoke(name) }
    }

    private fun launch(action: ProfileAction, errorOf: (Exception) -> Int, work: suspend () -> Unit) {
        if (!_running.compareAndSet(null, action)) return
        _error.value = null
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = errorOf(e)
            } finally {
                _running.value = null
            }
        }
    }

    /** Forgets a failure nobody took: a new session must not start with the last one's. */
    fun clearError() { _error.value = null }

    /** Hands over the failure to show, once. */
    fun takeError(): Int? = _error.getAndUpdate { null }
}

/** Shows [AccountActions] to the profile screen: its state is the graph's running action and the failure to show. */
class ProfileViewModel(private val actions: AccountActions) : ViewModel() {
    private val shownError = MutableStateFlow<Int?>(null)

    val state: StateFlow<ProfileState> = combine(actions.running, shownError) { busy, error -> ProfileState(busy, error) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProfileState(actions.running.value, null))

    init {
        // A failure that happened while no screen was there, or happens while this one is.
        viewModelScope.launch { actions.error.collect { if (it != null) actions.takeError()?.let { e -> shownError.value = e } } }
    }

    /** Saves [input] as the display name when [DisplayNameRule] accepts it; otherwise does nothing. */
    fun saveName(input: String) {
        val name = DisplayNameRule.clean(input) ?: return
        shownError.value = null
        actions.saveName(name)
    }

    fun openNameEditor() {}
    fun closeNameEditor() {}

    fun signOut() = run(ProfileAction.SigningOut)
    fun deleteAccount() = run(ProfileAction.Deleting)

    private fun run(action: ProfileAction) {
        shownError.value = null
        actions.start(action)
    }
}
