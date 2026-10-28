package org.example.app.calls.android

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Plays the ringtone while a call rings. A self-managed call gets no ringtone from the system. */
interface Ringer {
    /** Idempotent. */
    fun start()

    /** Idempotent. */
    fun stop()
}

/**
 * The phone's default ringtone, looping, or the ringing vibration, the way Android treats a call: the ringer
 * switch decides between sound, vibration and nothing, and Do Not Disturb silences it unless it lets calls from
 * anyone through.
 */
class SystemRinger(context: Context) : Ringer {
    enum class Alert { Ring, Vibrate, Silent }

    companion object {
        private val PATTERN = longArrayOf(0, 1_000, 1_000)

        fun alert(ringerMode: Int, interruptionFilter: Int, callsFromAnyone: Boolean): Alert {
            val dndSilences = when (interruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> !callsFromAnyone
                else -> false
            }
            if (dndSilences) return Alert.Silent
            return when (ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> Alert.Ring
                AudioManager.RINGER_MODE_VIBRATE -> Alert.Vibrate
                else -> Alert.Silent
            }
        }
    }

    private val context = context.applicationContext
    private var ringtone: Ringtone? = null
    private var vibrating = false

    @Synchronized
    override fun start() {
        if (ringtone != null || vibrating) return
        try {
            when (currentAlert()) {
                Alert.Ring -> {
                    val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        isLooping = true
                        play()
                    }
                }
                Alert.Vibrate -> {
                    vibrator()?.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
                    vibrating = true
                }
                Alert.Silent -> Unit
            }
        } catch (_: RuntimeException) {
            // No ringtone (a device without one, or a missing file): the notification still shows.
        }
    }

    @Synchronized
    override fun stop() {
        ringtone?.stop()
        ringtone = null
        if (vibrating) vibrator()?.cancel()
        vibrating = false
    }

    private fun currentAlert(): Alert {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val policy = notifications.notificationPolicy
        val callsFromAnyone = policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0 &&
            policy.priorityCallSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY
        return alert(audio.ringerMode, notifications.currentInterruptionFilter, callsFromAnyone)
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
}

/** The ringing call on screen and on the speaker, until it is answered, declined or ends. */
class RingingCall(private val context: Context, private val ringer: Ringer) {
    fun show(call: CallInfo) {
        CallNotifications.showIncoming(context, call)
        ringer.start()
    }

    fun dismiss() {
        ringer.stop()
        CallNotifications.cancelIncoming(context)
    }
}
