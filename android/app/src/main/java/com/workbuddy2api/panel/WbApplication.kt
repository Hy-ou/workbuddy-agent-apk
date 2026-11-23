package com.workbuddy2api.panel

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.workbuddy2api.panel.util.WebViewDumps
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程级启动逻辑：只做「崩溃现场保全」，不碰业务。
 *
 * 用户侧看到的「闪退回桌面」在 App 里是不可见的——进程已经死了，日志随进程一起没了。
 * 这里补两件只有 Application 层能做的事：
 *  1. [WebViewDumps.salvage]：把上次遗留的 WebView 崩溃转储抢进私有目录；
 *  2. `ApplicationExitInfo`（API 30+）：读回上次进程的退出原因（崩溃 / 低内存 / 被信号杀），
 *     落到 `files/exit-info.log`，不再依赖 root/Shizuku 去翻 tombstone。
 */
class WbApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { WebViewDumps.salvage(this) }
        recordExitReasons()
    }

    /**
     * 记录最近几次进程退出原因。
     *
     * 注意：WebView 的渲染进程是**独立进程**（`<provider>:sandboxed_process0`），它不会出现在
     * 本 App 的 exit-info 里；本 App 因为「渲染进程死亡未被认领」而被 abort 时，这里看到的是
     * `REASON_CRASH` / `REASON_SIGNALED`。配合 DshScreen 的 `onRenderProcessGone` 日志，
     * 就能把「渲染进程崩了」和「宿主因此自杀」这条因果链对上。
     *
     * 外层只做版本判断，API 30 的类型引用全部收进 [recordExitReasonsApi30]，避免低版本
     * 设备在类校验阶段就炸。
     */
    private fun recordExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { recordExitReasonsApi30() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExitReasonsApi30() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val lines = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            .filter { it.reason != ApplicationExitInfo.REASON_USER_REQUESTED }
            .map { info ->
                "${stamp.format(Date(info.timestamp))} reason=${reasonName(info.reason)}" +
                    " status=${info.status} pss=${info.pss} rss=${info.rss} desc=${info.description}"
            }
        if (lines.isEmpty()) return
        val log = File(filesDir, "exit-info.log")
        val kept = if (log.isFile) log.readLines().takeLast(MAX_LINES) else emptyList()
        // getHistoricalProcessExitReasons 每次启动都会把同一批历史再给一遍：按
        // 「时间戳 + reason」去重，否则每启动一次就往日志里堆一份重复记录。
        fun keyOf(line: String) = line.substringBefore(" reason=")
        val seen = kept.map { keyOf(it) }.toSet()
        val fresh = lines.filterNot { keyOf(it) in seen }
        if (fresh.isEmpty()) return
        log.writeText((kept + fresh).takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "RESOURCE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        else -> reason.toString()
    }

    private companion object {
        /** 只留最近若干行，避免每次启动都往日志里堆一份历史。 */
        const val MAX_LINES = 120
    }
}
