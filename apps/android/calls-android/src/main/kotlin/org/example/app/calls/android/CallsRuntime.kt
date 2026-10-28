package org.example.app.calls.android

import android.content.Context
import android.os.Bundle
import org.example.app.calls.android.telecom.FallbackCallController
import org.example.app.calls.session.MediaSessionFactory
import org.example.app.core.calls.CallEngine
import org.example.app.core.chats.Contact
import org.example.app.core.settings.UserSettings

/**
 * The app sets these in `Application.onCreate` (task I1). The Telecom service, the call service and the call
 * activity are created by the system with no constructor arguments, so they reach the app's objects here.
 */
object CallsRuntime {
    @Volatile var engine: CallEngine? = null

    @Volatile var controller: FallbackCallController? = null
}

/** Where the peer connection plugs into [LiveCallEngine]. */
object CallMedia {
    /**
     * [settings] is read when each call starts (the noise setting). Until E5 lands every call fails at setup,
     * cleanly: the engine turns the exception into a local failure.
     */
    // TODO(E5): return MediaSessionFactory { WebRtcMediaSession(context, settings()) } here; nothing else changes.
    // TODO(E5 part 2): WebRtcMediaSession.stats() maps E5's TransportStats (StatsMapper) into MediaStats.
    @Suppress("UNUSED_PARAMETER")
    fun factory(context: Context, settings: () -> UserSettings): MediaSessionFactory =
        MediaSessionFactory { throw UnsupportedOperationException("media session not available") }
}

/** [CallInfo] in intent and Telecom extras. IDs and names only. */
object CallExtras {
    private const val PREFIX = "org.example.app.calls."
    const val CALL_ID = PREFIX + "CALL_ID"
    private const val CONTACT_ID = PREFIX + "CONTACT_ID"
    private const val NAME = PREFIX + "NAME"
    private const val USERNAME = PREFIX + "USERNAME"
    private const val VIDEO = PREFIX + "VIDEO"

    fun bundle(call: CallInfo): Bundle = Bundle().apply { write(this, call) }

    fun write(b: Bundle, call: CallInfo) {
        b.putString(CALL_ID, call.callId)
        b.putString(CONTACT_ID, call.contact.id)
        b.putString(NAME, call.contact.displayName)
        b.putString(USERNAME, call.contact.username)
        b.putBoolean(VIDEO, call.video)
    }

    /** Reads the call from [b], or from a bundle nested one level down (Telecom wraps the extras it was given). */
    fun read(b: Bundle?): CallInfo? {
        if (b == null) return null
        val id = b.getString(CALL_ID)
        if (id != null) {
            val contact = Contact(b.getString(CONTACT_ID) ?: return null, b.getString(NAME) ?: "", b.getString(USERNAME) ?: "")
            return CallInfo(id, contact, b.getBoolean(VIDEO))
        }
        for (key in b.keySet()) {
            @Suppress("DEPRECATION")
            val nested = b.get(key) as? Bundle ?: continue
            nested.getString(CALL_ID) ?: continue
            return read(nested)
        }
        return null
    }

    fun displayName(context: Context, call: CallInfo): String =
        call.contact.displayName.ifEmpty { call.contact.username }.ifEmpty { context.getString(R.string.calls_unknown_caller) }
}
