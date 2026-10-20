package com.workbuddy2api.panel.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.workbuddy2api.panel.MainActivity
import com.workbuddy2api.panel.R

/**
 * 网关前台保活服务：内置 Go 网关运行期间常驻通知栏，
 * 让系统把本进程标记为高优先级，锁屏/切后台时不被回收（子进程随宿主一起存活）。
 */
class GatewayService : Service() {

    companion object {
        private const val CHANNEL_ID = "wb_gateway"
        private const val NOTIF_ID = 2
        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 服务被系统/用户停止时，同步停掉子进程，避免留下孤儿网关。
        runCatching { LocalGatewayManager.get(this).stop() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("WorkBuddy 网关运行中")
            .setContentText("本地 127.0.0.1 服务已就绪，Agent 与管理面板可直接使用")
            .setSmallIcon(R.drawable.ic_gateway_notify)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(CHANNEL_ID, "本地网关", NotificationManager.IMPORTANCE_LOW).apply {
            description = "WorkBuddy 网关运行状态"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
