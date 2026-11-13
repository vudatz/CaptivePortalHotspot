package com.portalhotspot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.portalhotspot.app.server.CaptivePortalServer
import com.portalhotspot.app.server.HotspotProxyServer

class PortalService : Service() {

    private var portalServer: CaptivePortalServer? = null
    private var proxyServer: HotspotProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        startServers()
        isRunning = true

        return START_STICKY
    }

    private fun startServers() {
        if (portalServer == null) {
            try {
                portalServer = CaptivePortalServer(this, 8080)
                portalServer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (proxyServer == null) {
            try {
                proxyServer = HotspotProxyServer(this, 8282)
                proxyServer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopServers() {
        portalServer?.stop()
        portalServer = null
        proxyServer?.stop()
        proxyServer = null
        isRunning = false
    }

    override fun onDestroy() {
        stopServers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hotspot Portal Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Captive Portal Hotspot đang chạy")
            .setContentText("Cổng Web 8080 & Proxy 8282 đang sẵn sàng điều phối kết nối")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "hotspot_portal_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        var isRunning = false
    }
}
