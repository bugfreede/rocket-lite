package com.bugfreede.rocketlite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.bugfreede.rocketlite.core.CoreState
import com.bugfreede.rocketlite.core.EngineHolder
import com.bugfreede.rocketlite.core.SingBoxConfig
import com.bugfreede.rocketlite.data.NodeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RocketVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.bugfreede.rocketlite.action.START"
        const val ACTION_STOP = "com.bugfreede.rocketlite.action.STOP"
        const val EXTRA_NODE_ID = "node_id"
        private const val CHANNEL_ID = "rocketlite_vpn"
        private const val NOTIFICATION_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        NodeStore.init(this)
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RocketLite VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        val node = NodeStore.find(intent.getStringExtra(EXTRA_NODE_ID))
        if (node == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWith("RocketLite 正在连接 ${node.name}…")
        scope.launch {
            EngineHolder.engine.start(SingBoxConfig.build(node))
            if (EngineHolder.engine.state.value == CoreState.CONNECTED) {
                // Only establish the TUN once the core is actually up, so a
                // missing core can never blackhole the device's traffic.
                // Phase 2: the fd is handed to libbox, which owns the packets.
                tunFd = Builder()
                    .setSession("RocketLite")
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)
                    .establish()
                startForegroundWith("RocketLite 已连接 ${node.name}")
            } else {
                stopTunnel()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopTunnel() {
        scope.launch { EngineHolder.engine.stop() }
        try { tunFd?.close() } catch (e: Exception) { /* already closed */ }
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWith(text: String) {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RocketLite")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
