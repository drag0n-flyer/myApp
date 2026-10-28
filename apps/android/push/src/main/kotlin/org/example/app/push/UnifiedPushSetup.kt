package org.example.app.push

import android.content.Context
import android.util.Base64
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint

/**
 * Starts UnifiedPush registration. The distributor answers later, through the receiver (F4), with a
 * [PushEndpoint]; [toRegistration] turns that into the upload. Never log an endpoint: it is a capability URL.
 */
class UnifiedPushSetup(private val context: Context) {
    companion object {
        const val INSTANCE = "default"

        /** Web Push keys arrive as base64url text. Returns null when they are missing or malformed. */
        fun toRegistration(endpoint: PushEndpoint): PushRegistration.UnifiedPush? {
            val keys = endpoint.pubKeySet ?: return null
            return try {
                PushRegistration.UnifiedPush(endpoint.url, decode(keys.pubKey), decode(keys.auth))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun decode(text: String): ByteArray =
            Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    /** True when the user, or the sole-distributor rule, already chose one. */
    fun distributorChosen(): Boolean = UnifiedPush.getSavedDistributor(context) != null

    /** Picks the only installed distributor when none is saved. Returns false when a choice is needed. */
    fun selectDistributor(): Boolean {
        if (distributorChosen()) return true
        val all = distributors()
        if (all.size != 1) return false
        UnifiedPush.saveDistributor(context, all.single())
        return true
    }

    /** Asks the distributor for an endpoint. [vapidKey] is `push.vapidKey` from `/v1/server`. */
    fun register(vapidKey: String) {
        UnifiedPush.register(context, INSTANCE, null, vapidKey)
    }

    fun unregister() {
        UnifiedPush.unregister(context, INSTANCE)
    }
}
