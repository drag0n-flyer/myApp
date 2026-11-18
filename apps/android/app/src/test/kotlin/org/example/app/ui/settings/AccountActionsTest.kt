package org.example.app.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.example.app.R
import org.example.app.core.account.AccountError
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountActionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var signOuts = 0
    private var deletes = 0
    private var gate: CompletableDeferred<Unit>? = null
    private var failure: Exception? = null
    private val actions = AccountActions(
        scope,
        signOut = { signOuts++; gate?.await(); failure?.let { throw it } },
        deleteAccount = { deletes++; gate?.await(); failure?.let { throw it } },
    )

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun viewModel(store: ViewModelStore) =
        ViewModelProvider(store, viewModelFactory { initializer { ProfileViewModel(actions) } })[ProfileViewModel::class.java]

    @Test
    fun aSecondActionWhileOneRunsIsIgnoredEvenFromAnotherViewModel() {
        gate = CompletableDeferred()
        val first = ViewModelStore()
        viewModel(first).signOut()
        first.clear() // the person left the screen; the sign-out keeps running
        val again = viewModel(ViewModelStore())
        idle()
        assertEquals(ProfileAction.SigningOut, again.state.value.busy)
        again.signOut()
        again.deleteAccount()
        assertEquals(1, signOuts)
        assertEquals(0, deletes)
        gate!!.complete(Unit)
        idle()
        assertNull(again.state.value.busy)
        gate = null
        again.deleteAccount() // free again
        assertEquals(1, deletes)
    }

    @Test
    fun anActionFailingAfterTheViewModelWasClearedIsReportedToANewOne() {
        gate = CompletableDeferred()
        failure = AccountError.Network
        val old = ViewModelStore()
        viewModel(old).signOut()
        old.clear()
        gate!!.complete(Unit) // fails with nobody on the screen
        val fresh = viewModel(ViewModelStore())
        idle()
        assertEquals(R.string.status_offline, fresh.state.value.error)
        assertNull(fresh.state.value.busy)
        val third = viewModel(ViewModelStore())
        idle()
        assertNull(third.state.value.error, "shown once, then cleared")
    }
}
