package com.layerbit.still.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.layerbit.still.MainActivity
import com.layerbit.still.R
import com.layerbit.still.StillApplication
import com.layerbit.still.text.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a batch alive while the user looks away, and says how it is going in the shade.
 *
 * The batch itself still runs in [JobRunner]; this service only holds the process in the
 * foreground for as long as a job is Running, shows "4 of 12" with a Cancel, and leaves a
 * "done" note when it ends. On Android 14 and later it is a short service, which needs no
 * special permission; if the system's time limit for those is reached, the service steps
 * down and the batch carries on as it did before this existed.
 */
class BatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runner = (application as StillApplication).jobRunner
        if (intent?.action == ACTION_CANCEL) { runner.cancel(); return START_NOT_STICKY }
        ensureChannel(this)
        val first = progress(runner.state.value as? JobState.Running)
        try {
            ServiceCompat.startForeground(this, ID_PROGRESS, first, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0)
        } catch (e: Exception) {
            // Not allowed to come to the foreground now: the batch simply runs without the shade.
            stopSelf(); return START_NOT_STICKY
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            runner.state.collectLatest { s ->
                val manager = getSystemService(NotificationManager::class.java)
                when (s) {
                    is JobState.Running -> manager.notify(ID_PROGRESS, progress(s))
                    is JobState.Finished, is JobState.Cancelled, is JobState.Crashed -> { finish(s); return@collectLatest }
                    JobState.Idle -> { ServiceCompat.stopForeground(this@BatchService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 14's limit for short services: step down and let the batch finish in the app. */
    override fun onTimeout(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(s: JobState) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val text = when (s) {
            is JobState.Finished -> when {
                s.failed.isEmpty() && s.skipped.isEmpty() -> Strings.plural(R.plurals.batch_done_all, s.produced.size, s.produced.size)
                else -> Strings.get(R.string.batch_done_some, s.produced.size, s.failed.size + s.skipped.size)
            }
            is JobState.Cancelled -> Strings.get(R.string.batch_cancelled)
            is JobState.Crashed -> s.reason
            else -> ""
        }
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Strings.get(R.string.batch_done_title))
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_DONE, note)
        stopSelf()
    }

    private fun progress(s: JobState.Running?): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, BatchService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s?.opTitle ?: Strings.get(R.string.batch_running))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, Strings.get(R.string.batch_cancel), cancel)
        if (s == null || s.unitsTotal <= 0) b.setProgress(0, 0, true)
        else {
            b.setProgress(s.unitsTotal, s.unitsDone, false)
            b.setContentText(Strings.get(R.string.batch_progress, s.unitsDone, s.unitsTotal) + if (s.label.isNotBlank()) " · " + s.label else "")
        }
        return b.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val CHANNEL = "batches"
        private const val ID_PROGRESS = 41
        private const val ID_DONE = 42
        private const val ACTION_CANCEL = "com.layerbit.still.CANCEL_BATCH"

        fun ensureChannel(context: Context) {

            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, Strings.get(R.string.batch_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }

        /** Called as a batch starts, from a screen the user is looking at. */
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, BatchService::class.java)) } catch (_: Exception) { }
        }
    }
}
