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

    /**
     * 是否 MIUI / HyperOS。
     *
     * 这些 ROM 的 GreezeManager 会在「锁屏」时冻结整个 UID（日志 reason=screen_off），
     * 即使应用持有前台服务和 wakelock 也不豁免——冻结后子进程（Go 网关）一起停摆，
     * 只有把应用的省电策略改成「无限制」才会被放过。所以设置页要单独引导。
     */
    fun isMiui(context: Context): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            runCatching { context.packageManager.getPackageInfo("com.miui.securitycenter", 0) }.isSuccess

    /** 打开「自启动管理」（HyperOS：授权后锁屏冻结通常一并解除）。 */
    fun openAutoStartSettings(context: Context) {
        val direct = Intent().setComponent(
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        if (runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        openAppDetails(context)
    }

    /** 打开本应用的系统详情页（HyperOS 的「省电策略 → 无限制」在这里）。 */
    fun openAppDetails(context: Context) {
        val detail = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        if (runCatching { context.startActivity(detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        requestIgnoreBatteryOptimization(context)
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
