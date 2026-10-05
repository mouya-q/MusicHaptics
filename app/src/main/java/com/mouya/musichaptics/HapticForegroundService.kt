package com.mouya.musichaptics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service that keeps the haptic engine alive when the dashboard
 * goes to background. Holds a partial wake lock to prevent CPU sleep during
 * music playback, and shows a persistent notification.
 *
 * HARDENING RULE (v5.4.0 hotfix):
 * Every external call here is wrapped in runCatching. A throw inside
 * Service.onCreate() runs on the process main thread with no caller to catch
 * it, so ANY failure — missing permission, vendor ROM refusing
 * startForegroundService, a restricted background-start policy — becomes an
 * immediate process-wide FATAL EXCEPTION. That is exactly what happened in
 * v5.4.0: WAKE_LOCK was never declared in AndroidManifest.xml, so
 * PowerManager.newWakeLock().acquire() threw SecurityException and the
 * Dashboard died the instant it opened. Keepalive is a best-effort feature;
 * it must never be able to kill the app.
 */
class HapticForegroundService : Service() {
    companion object {
        private const val TAG = "HapticFGService"
        private const val CHANNEL_ID = "haptics_keepalive"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            // Best effort. If the system refuses to start us (background start
            // restrictions, vendor ROM policy), the app must still open fine.
            runCatching {
                val intent = Intent(context, HapticForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "startForegroundService rejected, continuing without keepalive", it)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, HapticForegroundService::class.java))
            }.onFailure {
                Log.w(TAG, "stopService failed", it)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockHeld = false

    override fun onCreate() {
        super.onCreate()
        runCatching { createChannel() }
            .onFailure { Log.w(TAG, "createChannel failed", it) }
        runCatching { promoteToForeground() }
            .onFailure {
                // Foreground promotion can be refused (e.g. Android 12+ FGS
                // start restrictions). If we cannot go foreground we must stop,
                // otherwise the system kills us for a startForeground call
                // that never arrived.
                Log.e(TAG, "startForeground failed, stopping self", it)
                stopSelf()
            }
        acquireWakeLock()
    }

    /**
     * startForeground on its own is still risky on some ROMs (notification
     * channel blocked, POST_NOTIFICATIONS denied on 13+). Isolated so a failure
     * can be handled once, in onCreate.
     */
    private fun promoteToForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand startId=$startId")
        // Re-assert the wake lock: START_STICKY redelivery may have killed and
        // recreated us while the system thought we were still holding it.
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        Log.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * WHY runCatching MATTERS HERE:
     * Without android.permission.WAKE_LOCK this throws
     *   java.lang.SecurityException: Neither user N nor current process has
     *   android.permission.WAKE_LOCK
     * straight out of acquire() on the main thread, which kills the process.
     * Keepalive failing must be a log line, never a crash.
     */
    private fun acquireWakeLock() {
        if (wakeLockHeld) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicHapticsX::HapticEngine")
            // setReferenceCounted(false): one acquire() maps to one release().
            lock.setReferenceCounted(false)
            // 1 hour ceiling. Dashboard restarts us via onStart, which
            // re-acquires, so this can never become a permanent CPU pin.
            lock.acquire(60 * 60 * 1000L)
            wakeLock = lock
            wakeLockHeld = true
        }.onSuccess {
            Log.i(TAG, "Wake lock acquired")
        }.onFailure {
            wakeLock = null
            wakeLockHeld = false
            Log.w(TAG, "Wake lock unavailable, keepalive degraded to foreground notification only", it)
        }
    }

    private fun releaseWakeLock() {
        if (!wakeLockHeld) return
        runCatching {
            wakeLock?.release()
        }.onFailure {
            Log.w(TAG, "Wake lock release failed", it)
        }
        wakeLock = null
        wakeLockHeld = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Haptics Keep-Alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps music haptics running in background"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MusicHapticsX")
            .setContentText("Haptic engine running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }
}