package org.example.app.calls.android.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import org.example.app.calls.android.CallExtras
import org.example.app.calls.android.CallsRuntime

/**
 * Telecom's entry point for the app's self-managed calls. A call the engine already ended gets a failed
 * connection. A call Telecom cannot take (another app's call is
 * active, too many calls) goes to the plain controller through [FallbackCallController.telecomFailed].
 */
class AppConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val call = CallExtras.read(request?.extras) ?: return failed()
        // The engine ended the call before Telecom got here: it must not ring.
        if (AppConnections.isEnded(call.callId)) return failed()
        return AppConnection(applicationContext, call).also {
            it.setRinging()
            AppConnections.put(it)
        }
    }

    private fun failed(): Connection = Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        CallExtras.read(request?.extras)?.let { CallsRuntime.controller?.telecomFailed(it.callId) }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val call = CallExtras.read(request?.extras) ?: return failed()
        if (AppConnections.isEnded(call.callId)) return failed()
        return AppConnection(applicationContext, call).also {
            it.setDialing()
            AppConnections.put(it)
        }
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        CallExtras.read(request?.extras)?.let { CallsRuntime.controller?.telecomFailed(it.callId) }
    }
}
