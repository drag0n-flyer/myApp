package org.example.app.calls.android.telecom

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/** The app's self-managed phone account: its calls never show in the phone app or the system call log. */
object PhoneAccounts {
    const val ID = "app-calls"

    /** The address scheme of a call: `app:<accountID>`. */
    const val SCHEME = "app"

    const val CAPABILITIES = PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_VIDEO_CALLING

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, AppConnectionService::class.java), ID)

    fun account(context: Context): PhoneAccount {
        val label = context.applicationInfo.loadLabel(context.packageManager)
        return PhoneAccount.builder(handle(context), label)
            .setCapabilities(CAPABILITIES)
            .addSupportedUriScheme(SCHEME)
            .build()
    }

    /** Idempotent. Throws when Telecom refuses (no Telecom service, or the device does not allow self-managed calls). */
    fun register(context: Context): PhoneAccountHandle {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: throw IllegalStateException("no telecom")
        telecom.registerPhoneAccount(account(context))
        return handle(context)
    }
}
