package org.example.app.ui.media

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioManagerFocusTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shadow = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    @Test fun itAsksForTransientFocusForSpeechAndGivesItBack() {
        val focus = AudioManagerFocus(context)
        assertTrue(focus.request {})
        val asked = assertNotNull(shadow.lastAudioFocusRequest)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, asked.audioFocusRequest.focusGain)
        focus.abandon()
        assertTrue(shadow.lastAbandonedAudioFocusRequest != null)
    }

    @Test fun aRefusedRequestHoldsNothing() {
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val focus = AudioManagerFocus(context)
        assertFalse(focus.request {})
        focus.abandon()
        assertNull(shadow.lastAbandonedAudioFocusRequest)
    }

    @Test fun aLossIsReportedButARegainIsNot() {
        var lost = 0
        val focus = AudioManagerFocus(context)
        focus.request { lost++ }
        val listener = shadow.lastAudioFocusRequest!!.listener!!
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, lost)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, lost)
    }
}
