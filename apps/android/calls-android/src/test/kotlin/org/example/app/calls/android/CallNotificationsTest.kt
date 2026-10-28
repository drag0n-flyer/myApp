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
import org.example.app.calls.android.telecom.FallbackCallController
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
        assertTrue(shadowOf(n.fullScreenIntent).isActivityIntent)
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
    }

    @Test
    fun serviceTypesBeforeAndAfterAnswer() {
        val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        assertEquals(phone, CallService.serviceTypes(answered = false, video = true, sdk = 36))
        assertEquals(phone or mic, CallService.serviceTypes(answered = true, video = false, sdk = 36))
        assertEquals(phone or mic or camera, CallService.serviceTypes(answered = true, video = true, sdk = 36))
        // Android 10 has no microphone or camera types.
        assertEquals(phone, CallService.serviceTypes(answered = true, video = true, sdk = 29))
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
