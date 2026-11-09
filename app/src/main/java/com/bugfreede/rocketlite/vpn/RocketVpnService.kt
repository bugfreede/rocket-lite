package com.bugfreede.rocketlite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
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
    @Volatile
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        NodeStore.init(this)
        EngineHolder.init(this)
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RocketLite VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        scope.launch {
            EngineHolder.engine.state.collect { state ->
                if (!foreground) return@collect
                val text = when (state) {
                    CoreState.CONNECTING -> "RocketLite 正在连接…"
                    CoreState.CONNECTED -> "RocketLite 已连接"
                    CoreState.ERROR -> "RocketLite 连接失败：" + (EngineHolder.engine.error.value ?: "")
                    CoreState.IDLE -> "RocketLite"
                }
                updateNotification(text)
            }
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
        EngineHolder.libbox?.platform = LibboxPlatform(this)
        startForegroundWith("RocketLite 正在连接 ${node.name}…")
        val config = SingBoxConfig.build(node)
        scope.launch {
            EngineHolder.engine.start(config)
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
        scope.launch {
            EngineHolder.engine.stop()
        }
        foreground = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RocketLite")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWith(text: String) {
        foreground = true
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
