package com.ztdrop.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Explicitly enabled LAN device connectivity and cross-device messaging, not a hidden daemon. */
class KeepAliveService : Service() {
    companion object {
        const val ACTION_STOP = "com.ztdrop.android.STOP_KEEP_ALIVE"
        private const val CHANNEL = "lan_background"
        private const val NOTIFICATION_ID = 52110
    }
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val renewWakeLock = object : Runnable {
        override fun run() {
            val lock = wakeLock ?: return
            if (lock.isHeld) lock.release()
            lock.acquire(10 * 60_000L)
            handler.postDelayed(this, 5 * 60_000L)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (intent?.action == ACTION_STOP || !prefs.getBoolean("keep_alive", false)) {
            prefs.edit().putBoolean("keep_alive", false).apply()
            LanRuntime.requestKeepAlive(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            promote()
            LanRuntime.retainService(applicationContext)
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZTDrop:LAN").apply { setReferenceCounted(false) }
                renewWakeLock.run()
            }
            prefs.edit().remove("keep_alive_error").apply()
            return START_STICKY
        } catch (error: Exception) {
            prefs.edit().putBoolean("keep_alive", false)
                .putString("keep_alive_error", error.message ?: "前台服务启动失败").apply()
            LanRuntime.requestKeepAlive(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun promote() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "局域网后台运行", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持局域网设备连接与跨设备消息接收"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZTDrop 正在后台运行")
            .setContentText("保持局域网连接与消息接收 · 点击返回应用")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "关闭保活", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
            startForeground(NOTIFICATION_ID, notification, types)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        LanRuntime.releaseService()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
