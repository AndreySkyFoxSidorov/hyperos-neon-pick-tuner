package com.neontuner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.neontuner.audio.Reading
import com.neontuner.audio.TunerEngine
import com.neontuner.audio.TunerSettings
import com.neontuner.rear.RearOverlay
import java.util.Locale

/**
 * Foreground service (type = microphone). Keeps listening while the main screen is off or the
 * app is in the background, so the rear-display overlay keeps working when the phone is flipped.
 */
class TunerService : Service() {

    companion object {
        private const val CHANNEL = "tuner"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.neontuner.STOP"
        private const val ACTION_REFRESH = "com.neontuner.REFRESH"

        @Volatile
        var rearOverlayShowing = false
            private set

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, TunerService::class.java)) }
                .onFailure { Log.w("TunerService", "start failed", it) }
        }

        /** Re-read settings (rear on/off, inset) without restarting the microphone. */
        fun refresh(ctx: Context) {
            if (running) runCatching {
                ctx.startService(Intent(ctx, TunerService::class.java).setAction(ACTION_REFRESH))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TunerService::class.java))
        }

        /** Which tuner screens are currently visible ("main", "rear"). */
        private val visibleUis = HashSet<String>()

        fun isUiVisible(key: String) = key in visibleUis

        fun uiStarted(ctx: Context, key: String) {
            visibleUis.add(key)
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) start(ctx)
        }

        /** Microphone stops once no tuner screen is visible (unless the rear overlay is up). */
        fun uiStopped(ctx: Context, key: String) {
            visibleUis.remove(key)
            if (visibleUis.isEmpty() && !rearOverlayShowing) stop(ctx)
        }
    }

    private lateinit var overlay: RearOverlay
    private lateinit var nm: NotificationManager
    private var lastNotifText = ""
    private var lastNotifTime = 0L

    private val listener = TunerEngine.Listener { r -> onReading(r) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tuner", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        overlay = RearOverlay(this)
        if (!TunerEngine.isRunning) TunerEngine.settings = TunerSettings.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, buildNotification("Listening…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refuses a microphone FGS started from the background.
            Log.w("TunerService", "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            if (!TunerEngine.start(this)) {
                stopSelf(); return START_NOT_STICKY
            }
            TunerEngine.addListener(listener)
            running = true
        }
        if (TunerEngine.settings.rearEnabled) overlay.start() else overlay.stop()
        rearOverlayShowing = overlay.isShowing
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        rearOverlayShowing = false
        TunerEngine.removeListener(listener)
        overlay.stop()
        TunerEngine.stop()
        super.onDestroy()
    }

    private fun onReading(r: Reading) {
        rearOverlayShowing = overlay.isShowing
        if (!r.hasPitch) return
        val now = System.currentTimeMillis()
        val text = TunerEngine.noteName(r.midi) + TunerEngine.octave(r.midi) +
            String.format(Locale.US, "  %+.0f¢", r.cents)
        // Notifications are rate-limited by the system; ~2 updates per second is plenty.
        // Only update when the user allowed notifications (the app never asks for it).
        if (!nm.areNotificationsEnabled()) return
        if (text != lastNotifText && now - lastNotifTime > 500) {
            lastNotifText = text
            lastNotifTime = now
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tuner)
            .setContentTitle("Neon Tuner")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
