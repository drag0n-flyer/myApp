package org.example.app.calls.android

import android.content.Context
import android.os.SystemClock
import android.telecom.Connection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.app.calls.android.telecom.AppConnections
import org.example.app.calls.android.telecom.PhoneAccounts
import org.example.app.calls.android.telecom.TelecomCallController
import org.example.app.core.calls.CallEndReason
import org.example.app.core.chats.Contact
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on the API 29 and API 36 emulators: `./gradlew :calls-android:pixel6Api29DebugAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class TelecomSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun selfManagedIncomingCallShows() {
        val call = CallInfo("smoke-" + SystemClock.elapsedRealtime(), Contact("bob", "Bob", "bob"), video = false)
        val account = PhoneAccounts.account(context)
        assertTrue(account.hasCapabilities(PhoneAccounts.CAPABILITIES))
        val controller = TelecomCallController(context)
        controller.reportIncoming(call)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (AppConnections[call.callId] == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        val connection = assertNotNull(AppConnections[call.callId], "Telecom did not create the connection")
        assertEquals(Connection.STATE_RINGING, connection.state)
        assertEquals(Connection.PROPERTY_SELF_MANAGED, connection.connectionProperties and Connection.PROPERTY_SELF_MANAGED)
        controller.reportEnded(call.callId, CallEndReason.HungUp)
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
    }
}
