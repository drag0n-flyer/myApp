package org.example.app.calls.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import android.telecom.Connection
import android.telecom.ConnectionRequest
import org.example.app.calls.android.telecom.AppConnectionService
import org.example.app.calls.android.telecom.FallbackCallController
import org.example.app.calls.android.telecom.PlainCallController
import org.example.app.calls.android.telecom.TelecomCallController
import org.robolectric.Robolectric
import org.example.app.core.calls.CallEndReason
import org.example.app.core.chats.Contact
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val call = CallInfo("c1", Contact("bob", "Bob", "bob"), video = false)

    @Test
    fun incomingUsesCallStyleWithFullScreen() {
        val n = CallNotifications.incoming(context, call, fullScreen = true)
        assertEquals("calls", n.channelId)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertEquals(NotificationCompat.CallStyle.CALL_TYPE_INCOMING, n.extras.getInt(NotificationCompat.EXTRA_CALL_TYPE))
        assertNotNull(n.fullScreenIntent)
        // The full-screen intent opens the app's lock-screen call screen.
        val screen = shadowOf(n.fullScreenIntent)
        assertTrue(screen.isActivityIntent)
        assertEquals(IncomingCallActivity.ACTION_SHOW, screen.savedIntent.action)
        assertEquals(context.packageName, screen.savedIntent.`package`)
        assertEquals("c1", CallExtras.read(screen.savedIntent.extras)?.callId)
        // Answer and Decline open an activity directly: no broadcast trampoline.
        val expected = mapOf(
            NotificationCompat.EXTRA_ANSWER_INTENT to IncomingCallActivity.ACTION_ANSWER,
            NotificationCompat.EXTRA_DECLINE_INTENT to IncomingCallActivity.ACTION_DECLINE,
        )
        for ((key, action) in expected) {
            @Suppress("DEPRECATION")
            val pending = shadowOf(assertNotNull(n.extras.getParcelable<PendingIntent>(key), key))
            assertTrue(pending.isActivityIntent, key)
            assertEquals(IncomingCallActivity::class.java.name, pending.savedIntent.component?.className)
            assertEquals(action, pending.savedIntent.action)
        }
    }

    @Test
    fun fallsBackWithoutFullScreenPermission() {
        val n = CallNotifications.incoming(context, call, fullScreen = false)
        assertNull(n.fullScreenIntent)
        assertEquals("calls", n.channelId)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        // Heads-up: high priority on a high-importance channel, tapping it opens the call screen.
        @Suppress("DEPRECATION")
        assertEquals(Notification.PRIORITY_HIGH, n.priority)
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)
        // Not CallStyle (it would need a foreground service): plain Answer and Decline actions, both activities.
        assertEquals(0, n.extras.getInt(NotificationCompat.EXTRA_CALL_TYPE))
        val actions = n.actions.orEmpty().map { shadowOf(it.actionIntent) }
        assertEquals(listOf(IncomingCallActivity.ACTION_DECLINE, IncomingCallActivity.ACTION_ANSWER), actions.map { it.savedIntent.action })
        assertTrue(actions.all { it.isActivityIntent })
    }

    @Test
    fun serviceTypesBeforeAndAfterAnswer() {
        val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        assertEquals(phone or mic, CallService.serviceTypes(video = false, sdk = 36))
        assertEquals(phone or mic or camera, CallService.serviceTypes(video = true, sdk = 36))
        // Android 10 has no microphone or camera types.
        assertEquals(phone, CallService.serviceTypes(video = true, sdk = 29))

        // While it rings there is no foreground service at all, only the notification; it starts on answer.
        val app = shadowOf(context as android.app.Application)
        val ringer = FakeRinger()
        val plain = PlainCallController(context, ringer)
        plain.reportIncoming(call)
        assertNull(app.nextStartedService)
        assertEquals(1, shadowOf(notifications()).allNotifications.size)
        plain.reportAnswered("c1", video = false)
        assertEquals(CallService::class.java.name, app.nextStartedService?.component?.className)
        assertEquals(0, shadowOf(notifications()).allNotifications.size)
    }

    @Test
    fun ringsUntilAnsweredDeclinedOrEnded() {
        for (finish in listOf<(PlainCallController) -> Unit>(
            { it.reportAnswered("c1", video = false) },
            { it.reportEnded("c1", CallEndReason.Declined) },
            { it.reportEnded("c1", CallEndReason.AnsweredElsewhere) },
        )) {
            val ringer = FakeRinger()
            val plain = PlainCallController(context, ringer)
            plain.reportIncoming(call)
            assertTrue(ringer.ringing)
            finish(plain)
            assertTrue(!ringer.ringing)
            assertEquals(1, ringer.starts)
        }
    }

    @Test
    fun ringerFollowsSilentAndDoNotDisturb() {
        val all = android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        val priority = android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
        val none = android.app.NotificationManager.INTERRUPTION_FILTER_NONE
        val normal = android.media.AudioManager.RINGER_MODE_NORMAL
        assertEquals(SystemRinger.Alert.Ring, SystemRinger.alert(normal, all, callsFromAnyone = false))
        assertEquals(SystemRinger.Alert.Vibrate, SystemRinger.alert(android.media.AudioManager.RINGER_MODE_VIBRATE, all, false))
        assertEquals(SystemRinger.Alert.Silent, SystemRinger.alert(android.media.AudioManager.RINGER_MODE_SILENT, all, false))
        assertEquals(SystemRinger.Alert.Silent, SystemRinger.alert(normal, none, true))
        assertEquals(SystemRinger.Alert.Silent, SystemRinger.alert(normal, priority, callsFromAnyone = false))
        assertEquals(SystemRinger.Alert.Ring, SystemRinger.alert(normal, priority, callsFromAnyone = true))
    }

    @Test
    fun telecomConnectionForAnEndedCallFails() {
        // The engine ends the call before Telecom asks for its connection: it must not ring or dial.
        val controller = TelecomCallController(context, FakeRinger())
        controller.reportEnded("c1", CallEndReason.Declined)
        val service = Robolectric.buildService(AppConnectionService::class.java).create().get()
        val request = ConnectionRequest(null, null, CallExtras.bundle(call))
        assertEquals(Connection.STATE_DISCONNECTED, service.onCreateIncomingConnection(null, request).state)
        assertEquals(Connection.STATE_DISCONNECTED, service.onCreateOutgoingConnection(null, request).state)
        val live = ConnectionRequest(null, null, CallExtras.bundle(call.copy(callId = "c2")))
        val ringing = service.onCreateIncomingConnection(null, live)
        assertEquals(Connection.STATE_RINGING, ringing.state)
        controller.reportEnded("c2", CallEndReason.HungUp)
        assertEquals(Connection.STATE_DISCONNECTED, ringing.state)
    }

    private fun notifications() = context.getSystemService(android.app.NotificationManager::class.java)

    private class FakeRinger : Ringer {
        var ringing = false
        var starts = 0
        override fun start() { if (!ringing) starts++; ringing = true }
        override fun stop() { ringing = false }
    }

    @Test
    fun telecomFailureUsesPlainController() {
        val plain = Recording()
        val failing = object : CallController by Recording() {
            override fun reportIncoming(call: CallInfo) = throw SecurityException("no phone account")
        }
        val controller = FallbackCallController(failing, plain)
        controller.reportIncoming(call)
        controller.reportAnswered("c1", video = false)
        controller.reportEnded("c1", CallEndReason.HungUp)
        assertEquals(listOf("incoming:c1", "answered:c1", "ended:c1"), plain.log)

        // Telecom refuses the call later (onCreateIncomingConnectionFailed): the plain controller takes over.
        val telecom = Recording()
        val plain2 = Recording()
        val controller2 = FallbackCallController(telecom, plain2)
        controller2.reportIncoming(call)
        controller2.telecomFailed("c1")
        assertEquals(listOf("incoming:c1"), plain2.log)
        controller2.reportActive("c1")
        assertEquals("active:c1", plain2.log.last())
        assertEquals(listOf("incoming:c1"), telecom.log)
    }

    private class Recording : CallController {
        val log = mutableListOf<String>()
        override fun reportIncoming(call: CallInfo) { log += "incoming:${call.callId}" }
        override fun reportOutgoing(call: CallInfo) { log += "outgoing:${call.callId}" }
        override fun reportAnswered(callId: String, video: Boolean) { log += "answered:$callId" }
        override fun reportActive(callId: String) { log += "active:$callId" }
        override fun reportEnded(callId: String, reason: CallEndReason) { log += "ended:$callId" }
        override fun reportMissed(call: CallInfo) { log += "missed:${call.callId}" }
    }
}
