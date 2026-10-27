package org.example.app.push

import android.content.Context
import org.example.app.contracts.NotificationOutcome

/** The real effects: notifications, the expedited fetch, the missed call, and the monitor. */
class AndroidPushEffects(
    context: Context,
    private val missedCalls: MissedCallRecorder,
    private val monitor: MissedPushMonitor = MissedPushMonitor(context),
) : PushEffects {
    private val context = context.applicationContext
    private val notifier = MessageNotifier(this.context)

    override fun show(outcome: NotificationOutcome) = notifier.show(outcome)

    override fun enqueueInboxFetch() = InboxFetchWorker.enqueue(context)

    override suspend fun recordMissedCall(callId: String, fromAccountId: String) =
        missedCalls.recordMissedCall(callId, fromAccountId)

    override fun pushHandled(highPriority: Boolean, showedSomething: Boolean) =
        monitor.pushHandled(highPriority, showedSomething)
}
