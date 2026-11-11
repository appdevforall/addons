package org.appdevforall.shareyourapps.server

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

/**
 * Keeps an import running while the app is off the screen (design, Appendix E.2): a foreground
 * service, so Android doesn't freeze or kill the process mid-transfer. It shows the import's progress
 * in its notification, and stops itself once the import is no longer busy. The work itself is
 * [ImportJob]'s; this only keeps the process alive.
 */
class ImportService : Service() {
    private val listener: () -> Unit = { update() }
    /** Observing ImportJob: once per service, though a retry or a second import starts it again while it runs. */
    private var observing = false
    /** The question the heads-up notification shows, so it's posted once, not on every progress update. */
    private var shownQuestion: ImportJob.Question? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Cancel, as on the import screen. The service is already in the foreground then,
        // or there's nothing left to cancel and it stops.
        if (intent?.action == ACTION_CANCEL) {
            ImportJob.cancel()
            if (!observing) stopSelf()
            return START_NOT_STICKY
        }
        val n = notification("Importing a store…", null)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, n)
        if (!observing) { observing = true; ImportJob.observe(listener) } else update()
        return START_NOT_STICKY
    }

    private fun update() {
        val s = ImportJob.snapshot
        // Only an MCP import's question: a person importing is on the import screen, which asks it.
        showQuestion(ImportJob.question?.takeIf { ImportJob.startedByMcp && s.busy })
        if (!s.busy) { stopSelf(); return }
        val text = when (s.phase) {
            ImportJob.Phase.WAITING -> "Waiting for the owner to allow it…"
            ImportJob.Phase.RECEIVING -> if (s.total > 0) "Receiving ${s.storeName ?: "the store"}: ${s.received * 100 / s.total}%" else "Receiving…"
            else -> "Importing a store…"
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION, notification(text, s.storeName))
    }

    override fun onDestroy() {
        ImportJob.remove(listener); observing = false
        showQuestion(null)
        super.onDestroy()
    }

    /** [store]: the store's name, once known (it arrives when receiving starts), for the title. */
    private fun notification(text: String, store: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Importing a store", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, ImportActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 2, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(store?.let { "Importing $it" } ?: "Share Your Apps Server")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .setOngoing(true)
            .build()
    }

    /**
     * An MCP import's question (an older copy replacing a newer one), which no screen may be showing: a heads-up
     * notification that opens the import screen, which asks it. Null removes it. The import waits 2 minutes.
     */
    private fun showQuestion(q: ImportJob.Question?) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (q == null) { shownQuestion = null; nm.cancel(QUESTION_TAG, NOTIFICATION); return }
        if (q === shownQuestion) return
        shownQuestion = q
        if (nm.getNotificationChannel(QUESTION_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(QUESTION_CHANNEL, "Import questions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When an import started by an MCP client needs a person: replacing a newer copy with an older one."
            })
        val open = PendingIntent.getActivity(this, 3, Intent(this, ImportActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = "${q.message} Tap to answer within 2 minutes."
        nm.notify(QUESTION_TAG, NOTIFICATION, Notification.Builder(this, QUESTION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(q.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        private const val CHANNEL = "import"
        private const val NOTIFICATION = 2
        /** High importance, for a heads-up; the "import" channel is low, and a channel's importance can't be raised. */
        private const val QUESTION_CHANNEL = "import_question"
        /** With [NOTIFICATION]: a tag keeps it apart from the progress notification, and from other ids in the app. */
        private const val QUESTION_TAG = "question"
        const val ACTION_CANCEL = "org.appdevforall.shareyourapps.server.IMPORT_CANCEL"

        /** Whether Android let the service start: it refuses foreground starts while the app is in the background. */
        fun start(context: Context): Boolean =
            try { context.startForegroundService(Intent(context, ImportService::class.java)) != null } catch (e: Exception) { false }
    }
}
