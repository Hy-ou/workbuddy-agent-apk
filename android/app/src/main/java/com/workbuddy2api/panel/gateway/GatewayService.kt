package com.workbuddy2api.panel.gateway

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.workbuddy2api.panel.MainActivity
import com.workbuddy2api.panel.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 网关前台保活服务。
 *
 * 锁屏后网关"停止工作"是四件事叠加，这里逐条兜住：
 *
 * 1. **CPU 休眠**：前台服务只防进程被回收，不防 CPU 休眠。屏幕一灭、又没有
 *    wakelock，SoC 直接 suspend，Go 进程的定时任务（签到/保活/余额刷新）与
 *    网络 I/O 全部冻结。所以服务存活期间自己持一把 PARTIAL_WAKE_LOCK。
 * 2. **前台服务配额**：Android 15+ 的 `dataSync` 类型有 6 小时 / 24 小时后台
 *    配额，用满后系统回调 [onTimeout]，默认实现直接抛异常杀死进程。改用
 *    `specialUse`（本地常驻服务语义，无配额），并重写 [onTimeout] 兜底。
 * 3. **进程死亡**：看门狗每 [HEARTBEAT_MS] 探一次网关，进程退出后立即重拉
 *    （[LocalGatewayManager.ensureRunning] 幂等）。
 * 4. **服务被系统回收**：onDestroy 只在"用户主动停止"时才杀网关；系统回收时
 *    保留现场，靠 START_STICKY 重建 + onStartCommand 重新拉起网关。
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "WbGateway"
        private const val CHANNEL_ID = "wb_gateway"
        private const val NOTIF_ID = 2
        private const val WAKE_TAG = "wb:gateway"

        /** 看门狗心跳：30s 检查一次网关进程是否还活着。 */
        private const val HEARTBEAT_MS = 30_000L

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

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    /** 看门狗：网关进程被 LMK / ROM 清理 / 自身崩溃后自动重拉。 */
    private val heartbeat = object : Runnable {
        override fun run() {
            val manager = LocalGatewayManager.get(this@GatewayService)
            // 部分 ROM 冻结 UID 时会把 wakelock 置为 DISABLED、解冻后再放回来；
            // 这里兜一手，确认锁真的还在（幂等，isHeld 时直接返回）。
            acquireWakeLock()
            if (!manager.isRunning()) {
                Log.w(TAG, "看门狗：网关未运行，重新拉起")
                scope.launch { runCatching { manager.ensureRunning() } }
            }
            runCatching { notificationManager()?.notify(NOTIF_ID, buildNotification()) }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被 START_STICKY 重建时网关进程可能已经不在了，这里必须重拉一次，
        // 否则会出现"通知写着运行中、实际没有网关"的假活状态。
        startForegroundCompat(buildNotification())
        scope.launch { runCatching { LocalGatewayManager.get(this@GatewayService).ensureRunning() } }
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        acquireWakeLock()
        return START_STICKY
    }

    /**
     * Android 15+ 前台服务超时回调。
     *
     * 默认实现会抛 ForegroundServiceDidNotStopInTimeException 直接杀进程（锁屏过夜必现），
     * 这里改成重新提交前台状态 + 重拉网关，绝不让系统走默认路径。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "前台服务超时（type=$fgsType），重新提交前台状态")
        startForegroundCompat(buildNotification())
        scope.launch { runCatching { LocalGatewayManager.get(this@GatewayService).ensureRunning() } }
    }

    /** 用户从最近任务划掉 App：不跟着停，交给看门狗续命。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "任务被划掉，保持网关存活")
        scope.launch { runCatching { LocalGatewayManager.get(this@GatewayService).ensureRunning() } }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        releaseWakeLock()
        // 只有用户主动停止（断开连接 / 关网关）才杀子进程；系统回收时保留现场，
        // 等 START_STICKY 把服务重建起来后由 onStartCommand 重新拉起网关。
        val manager = LocalGatewayManager.get(this)
        if (manager.consumeStopRequest()) {
            manager.stop()
        }
        super.onDestroy()
    }

    /** API 34+ 需要显式声明前台服务类型；specialUse 对应 manifest 的同一个类型。 */
    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * 持一把部分唤醒锁，锁屏后 CPU 不休眠。
     *
     * 这是"网关 7×24 常驻"的必要代价：前台服务不会替应用挡住 suspend，
     * 只有 wakelock 能。用户主动停止网关时（服务销毁）随即释放。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        Log.i(TAG, "已持有 PARTIAL_WAKE_LOCK，锁屏后网关继续运行")
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val status = LocalGatewayManager.get(this).status.value
        val text = when (status) {
            is GatewayStatus.Running ->
                "本地 127.0.0.1:${status.port} 已就绪 · 锁屏保活中，Agent 与管理面板可直接使用"
            is GatewayStatus.Starting -> "正在启动本地网关…"
            is GatewayStatus.Failed -> "网关异常，正在自动恢复：" + status.message.take(60)
            GatewayStatus.Stopped -> "网关未运行，正在自动恢复…"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("WorkBuddy 网关运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_gateway_notify)
            .setContentIntent(pending)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager() ?: return
        val channel = NotificationChannel(CHANNEL_ID, "本地网关", NotificationManager.IMPORTANCE_LOW).apply {
            description = "WorkBuddy 网关运行状态"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
