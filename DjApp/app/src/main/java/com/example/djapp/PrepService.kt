package com.example.djapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the app (and the bound AI process) alive while tracks are being prepared in the background, so budget phones
 * with aggressive memory killers (e.g. Itel) do not stop a long AI MAX job when the screen turns off or you switch app.
 * It starts with the first queued track and stops by itself when the queue is empty.
 */
class PrepService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!PrepQueue.running) { stopSelf(); return }
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build()) } catch (_: Exception) {}
            handler.postDelayed(this, 2000)
        }
    }

    private fun build(): Notification {
        val text = when {
            PrepQueue.paused -> "Paused while a deck plays · ${PrepQueue.current}"
            PrepQueue.current.isNotEmpty() -> "${(PrepQueue.progress * 100).toInt()}% · ${PrepQueue.current} · ${PrepQueue.queue.size} queued"
            else -> "Getting ready…"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MAXIDO DJ is preparing stems")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Preparing stems", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, build())
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (wake == null) {
            try {
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maxido:prep")
                    .apply { setReferenceCounted(false); acquire(12L * 60 * 60 * 1000) }
            } catch (_: Exception) {}
        }
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object { const val CHANNEL = "prep"; const val ID = 4107 }
}
