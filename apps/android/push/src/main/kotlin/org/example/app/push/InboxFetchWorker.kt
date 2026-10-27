package org.example.app.push

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.example.app.contracts.NotificationOutcome

/** The long part of a wake: fetch the inbox and show what it holds. Runs as expedited work. */
object InboxFetchRunner {
    /** Returns false when the fetch failed (the next push or app start fetches again). */
    suspend fun run(context: Context): Boolean {
        val fetcher = PushRuntime.inboxFetcher ?: return true
        val notifier = MessageNotifier(context)
        val monitor = MissedPushMonitor(context)
        val outcomes = try {
            fetcher.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A push is behind this fetch: say so, as the iPhone does. A later retry replaces the notice.
            notifier.show(NotificationOutcome.Generic)
            monitor.fetchFinished(showedSomething = true)
            return false
        }
        val real = outcomes.filter { it != NotificationOutcome.Nothing && it != NotificationOutcome.FetchInbox }
        if (real.isEmpty()) {
            // The push is counted as shown only because the generic notice is shown for it.
            if (monitor.fetchFinished(showedSomething = true) > 0) notifier.show(NotificationOutcome.Generic)
        } else {
            notifier.cancelGeneric()
            real.forEach { notifier.show(it) }
            monitor.fetchFinished(showedSomething = true)
        }
        return true
    }
}

class InboxFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val UNIQUE_NAME = "inbox-fetch"
        private const val FOREGROUND_ID = 0x1F4

        fun request(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<InboxFetchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // Over quota, run as ordinary work instead of failing: the push is not lost, only later.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        /** Pushes that arrive while a fetch is queued or running share that fetch. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
        }

        /** Only Android 10 and 11 use this: expedited work there runs as a short foreground service. */
        fun foregroundNotification(context: Context): Notification {
            NotificationChannels.ensure(context)
            return NotificationCompat.Builder(context, NotificationChannels.SERVICE)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setContentTitle(context.getString(R.string.push_fetch_title))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    override suspend fun doWork(): Result =
        if (InboxFetchRunner.run(applicationContext)) Result.success() else Result.retry()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = foregroundNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_ID, notification)
        }
    }
}
