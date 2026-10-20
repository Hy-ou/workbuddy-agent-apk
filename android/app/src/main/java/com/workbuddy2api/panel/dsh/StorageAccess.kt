package com.workbuddy2api.panel.dsh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）相关工具。
 *
 * 用途：DSH 内核里的 bash / 文件 / 检索工具都跑在本 App 的 UID 下，
 * 只有拿到该权限后才能自由读写 /storage/emulated/0 下的手机文件。
 */
object StorageAccess {

    /** 外部共享存储根目录（/storage/emulated/0）。 */
    fun sharedRoot(): java.io.File = Environment.getExternalStorageDirectory()

    /** DSH 的默认共享工作区：/storage/emulated/0/WorkBuddy。 */
    fun sharedWorkspaceName(): String = "WorkBuddy"

    /** 是否已拥有「所有文件访问」权限（API 30+ 用 MANAGE_EXTERNAL_STORAGE，更低版本退化为读写权限）。 */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            legacyStorageGranted(context)
        }

    /** Android 10 及以下的旧式读写权限是否已授予。 */
    fun legacyStorageGranted(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    /** 打开系统的「所有文件访问」授权页；低版本回退到应用详情页。 */
    fun allFilesAccessIntent(context: Context): Intent {
        val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        // 部分 ROM 没有逐应用入口，回退到全局列表页。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return direct
    }

    /** 全局「所有文件访问」列表页（逐应用页不可用时的兜底）。 */
    fun globalAllFilesAccessIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    fun openAllFilesAccessSettings(context: Context) {
        val intent = allFilesAccessIntent(context)
        val ok = runCatching { context.startActivity(intent) }.isSuccess ||
            runCatching { context.startActivity(globalAllFilesAccessIntent() ?: return) }.isSuccess
        if (!ok) {
            // 极少数 ROM 两个入口都没有，退回应用详情页。
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 权限状态文案。 */
    fun statusText(granted: Boolean): String =
        if (granted) "已授予：DSH 可检索与操作手机文件" else "未授予：DSH 只能访问 App 私有目录"
}
