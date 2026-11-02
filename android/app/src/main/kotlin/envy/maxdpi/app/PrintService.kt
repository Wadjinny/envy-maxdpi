package envy.maxdpi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import envy.maxdpi.CanceledOnPrinterException
import envy.maxdpi.IppClient
import envy.maxdpi.PrintOptions
import envy.maxdpi.Printing
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What the UI shows about printing. Updated on the main thread. */
object PrintState {
    data class Snapshot(
        val active: Boolean = false,
        val title: String = "",
        val message: String = "",
        val fraction: Float = 0f,
        val queued: Int = 0,
        val finished: Boolean = false,
        val failed: Boolean = false,
    )

    var current by mutableStateOf(Snapshot())
        internal set
}

/** A print job as handed to the service: plain data, so it fits in an Intent. */
data class JobSpec(
    val host: String,
    val title: String,
    val options: PrintOptions,
    val pages: List<PageRef>,
    /** Ready-made pages (e.g. the calibration target), printed instead of [pages]. */
    val contents: List<envy.maxdpi.PageContent> = emptyList(),
)

/**
 * Prints in the foreground-service sense: keeps running with a notification when the app is
 * closed, and holds Wi-Fi and CPU awake. Jobs run one at a time.
 */
class PrintService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val queued = AtomicInteger(0)
    @Volatile private var cancelCurrent = false
    private var lastNotify = 0L
    private var lastNotifyText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelCurrent = true
                post { it.copy(message = "Cancelling…") }
            }
            ACTION_PRINT -> {
                val job = pending.removeFirstOrNull() ?: return START_NOT_STICKY
                startInForeground(notification(job.title, "Waiting for the printer…", -1f))
                queued.incrementAndGet()
                executor.execute { run(job) }
            }
        }
        return START_NOT_STICKY
    }

    private fun run(job: JobSpec) {
        cancelCurrent = false
        post { PrintState.Snapshot(active = true, title = job.title, message = "Preparing…", queued = queued.get() - 1) }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "envy:print").apply { acquire(2 * 60 * 60_000L) }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifi = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "envy:print").apply { acquire() }
        var failed = false
        val message = try {
            val address = PrinterFinder.resolve(this, job.host)
            val contents = job.contents.ifEmpty { job.pages.map { Documents.content(it) } }
            var problem: String? = null
            var lastFraction = 0f
            var lastText = "Preparing…"
            fun show() = update(job, lastFraction, problem?.let { "Paused: $it" } ?: lastText)
            Printing.print(address, job.title, contents, job.options, progress = { fraction, page, pages ->
                lastFraction = fraction.toFloat()
                // The printer keeps printing from its buffer after the last data is sent.
                lastText = if (fraction >= 1.0) "Finishing on the printer…" else "Printing page $page of $pages"
                show()
            }, isCancelled = { cancelCurrent }, onStatus = { problem = it; show() })
            "Printed"
        } catch (e: CanceledOnPrinterException) {
            failed = true
            "Cancelled on the printer."
        } catch (e: CancellationException) {
            failed = true
            "Cancelled. The printer ejects the partly printed page."
        } catch (e: Throwable) {
            failed = true
            friendlyError(e)
        } finally {
            wake.release()
            wifi.release()
        }
        val left = queued.decrementAndGet()
        post { it.copy(active = left > 0, finished = true, failed = failed, message = message, fraction = if (failed) it.fraction else 1f, queued = left) }
        if (left == 0) {
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                manager().notify(DONE_ID, notification(job.title, message, null, ongoing = false))
                stopSelf()
            }
        }
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is IllegalStateException -> e.message ?: "Can't find the printer."
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.SocketTimeoutException ->
            "Can't reach the printer. Check that it's on and on the same Wi-Fi."
        is IppClient.IppException -> when (e.statusCode) {
            0x0507 -> "The printer stayed busy with another job. Try again in a moment."
            else -> "The printer refused the job (${e.message})."
        }
        is java.io.IOException -> "The connection to the printer was lost (${e.message ?: e.javaClass.simpleName})."
        else -> "Failed: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun update(job: JobSpec, fraction: Float, message: String) {
        post { it.copy(active = true, fraction = fraction, message = message) }
        val now = System.currentTimeMillis()
        // Throttle progress-only updates; a new message (e.g. "Paused: ...") always goes out.
        if (now - lastNotify > 1000 || message != lastNotifyText) {
            lastNotify = now
            lastNotifyText = message
            manager().notify(ID, notification(job.title, message, fraction))
        }
    }

    private fun post(change: (PrintState.Snapshot) -> PrintState.Snapshot) =
        main.post { PrintState.current = change(PrintState.current) }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
    }

    private fun manager() = getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "Printing", NotificationManager.IMPORTANCE_LOW))
    }

    /** [fraction] null: no bar; negative: indeterminate. */
    private fun notification(title: String, text: String, fraction: Float?, ongoing: Boolean = true): Notification {
        manager()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setColor(0xFF4F46E5.toInt())
        if (fraction != null) b.setProgress(1000, (fraction.coerceIn(0f, 1f) * 1000).toInt(), fraction < 0)
        if (ongoing) {
            val cancel = PendingIntent.getService(this, 1, Intent(this, PrintService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        }
        return b.build()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PRINT = "envy.maxdpi.PRINT"
        private const val ACTION_CANCEL = "envy.maxdpi.CANCEL"
        private const val CHANNEL = "printing"
        private const val ID = 1
        private const val DONE_ID = 2

        // Jobs hold live objects (pages), so they're passed in-process rather than through extras.
        private val pending = ArrayDeque<JobSpec>()

        fun print(context: Context, job: JobSpec) {
            pending.addLast(job)
            context.startForegroundService(Intent(context, PrintService::class.java).setAction(ACTION_PRINT))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, PrintService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
