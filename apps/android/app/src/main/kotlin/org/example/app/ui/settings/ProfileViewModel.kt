package org.example.app.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.account.AccountError

enum class ProfileAction { SigningOut, Deleting }

data class ProfileState(
    /** The action running now; both rows are off while it runs. */
    val busy: ProfileAction? = null,
    @StringRes val error: Int? = null,
)

/**
 * Runs sign-out and account deletion so that a rotation does not lose them or leave the rows stuck: the state lives
 * here, not in the screen. The actions themselves run in the graph's scope (they finish even if the screen goes).
 */
class ProfileViewModel(
    private val signOutAction: suspend () -> Unit,
    private val deleteAction: suspend () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    fun signOut() = run(ProfileAction.SigningOut, signOutAction)
    fun deleteAccount() = run(ProfileAction.Deleting, deleteAction)

    private fun run(action: ProfileAction, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.value = ProfileState(busy = action)
        viewModelScope.launch {
            try {
                block()
                _state.value = ProfileState() // the app goes back to the welcome screen
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ProfileState(error = if (e is AccountError.Network) R.string.status_offline else R.string.err_server)
            }
        }
    }
}
