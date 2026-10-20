package com.workbuddy2api.panel.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 设备能力与系统设置入口（电池白名单、ABI、WebView 版本）。 */
object DeviceEnv {

    /** 是否支持 DSH 内核（需要 arm64 运行时）。 */
    fun supportsDsh(): Boolean =
        Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }

    /** ABI 描述，用于设置页展示。 */
    fun abiSummary(): String = Build.SUPPORTED_ABIS.joinToString(", ")

    /** 是否已忽略电池优化（已加入白名单）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    /** 申请忽略电池优化；部分 ROM 无逐应用弹窗，回退到设置列表。 */
    fun requestIgnoreBatteryOptimization(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val ok = runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess ||
            runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 从 WebView UA 里取 Chromium 主版本号；取不到返回 0。 */
    fun chromeMajor(userAgent: String?): Int {
        if (userAgent.isNullOrBlank()) return 0
        val m = Regex("Chrome/(\\d+)").find(userAgent) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    /** DSH 前端要求的 Chromium 下限。 */
    const val MIN_CHROME_FOR_DSH = 80
}
