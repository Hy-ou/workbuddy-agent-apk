package com.workbuddy2api.panel.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 应用通知中心：供 Agent「任务完成 / 需要关注」时主动推送。
 *
 * 只需要通知权限（Android 13+ 为运行时权限，MainActivity 启动时会申请）。
 */
object NotificationCenter {

    private const val CHANNEL_AGENT = "wb_agent_notify"
    private const val NOTIFY_ID_BASE = 42000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_AGENT) == null) {
            val ch = NotificationChannel(
                CHANNEL_AGENT,
                "Agent 通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "AI 任务完成或需要你关注时提醒" }
            nm.createNotificationChannel(ch)
        }
    }

    /** 发一条 Agent 通知；返回是否成功（未授权通知权限时通常静默失败）。 */
    fun post(context: Context, title: String, body: String): Boolean = runCatching {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_AGENT)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val n = builder
            .setContentTitle(title.ifBlank { "WorkBuddy" })
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        nm.notify(NOTIFY_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), n)
        true
    }.getOrDefault(false)
}
