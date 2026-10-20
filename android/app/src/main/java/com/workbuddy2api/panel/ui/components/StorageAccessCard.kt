package com.workbuddy2api.panel.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.workbuddy2api.panel.dsh.StorageAccess
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import kotlinx.coroutines.launch

/**
 * 「所有文件访问权限」的申请入口。
 *
 * DSH 内核（bash / 文件 / 检索工具）以本 App 的 UID 运行，只有拿到该权限后
 * 才能真正读写手机里的 /storage/emulated/0 目录。
 */
@Composable
private fun rememberAllFilesAccess(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(StorageAccess.hasAllFilesAccess(context)) }

    // 从系统授权页返回后刷新状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = StorageAccess.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { granted = StorageAccess.hasAllFilesAccess(context) }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = StorageAccess.hasAllFilesAccess(context) }

    val request: () -> Unit = remember(granted) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 必须去系统「所有文件访问」页授权，无法用运行时弹窗。
                val launched = runCatching { settingsLauncher.launch(StorageAccess.allFilesAccessIntent(context)) }
                if (launched.isFailure) StorageAccess.openAllFilesAccessSettings(context)
            } else {
                legacyLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            }
            scope.launch {
                snackbar.showSnackbar(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        "请在系统页面打开「允许管理所有文件」，返回后自动生效"
                    } else {
                        "授权存储权限后即可让 DSH 读取手机文件"
                    },
                )
            }
        }
    }
    return granted to request
}

/** 设置页里的完整卡片：状态 + 说明 + 授权按钮。 */
@Composable
fun AllFilesAccessCard(modifier: Modifier = Modifier) {
    val wb = LocalWbColors.current
    val (granted, request) = rememberAllFilesAccess()
    WbCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("所有文件访问", style = MaterialTheme.typography.titleSmall, color = wb.ink)
                Text(
                    StorageAccess.statusText(granted),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) wb.ok else wb.ink3,
                )
            }
            StatusChip(
                text = if (granted) "已授予" else "未授予",
                tone = if (granted) Tone.Ok else Tone.Warn,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "DSH 内核的 bash、文件与检索工具都运行在本 App 的进程里；授予后它才能浏览、读取和" +
                "修改手机存储（/storage/emulated/0），否则只能访问 App 私有目录。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink2,
        )
        Spacer(Modifier.height(6.dp))
        Mono("共享工作区: /storage/emulated/0/${StorageAccess.sharedWorkspaceName()}")
        Spacer(Modifier.height(10.dp))
        WbButton(
            text = if (granted) "重新检查授权状态" else "去授予所有文件访问",
            onClick = { if (granted) request() else request() },
            primary = !granted,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (granted) {
                "已生效：DSH 启动时会把共享工作区切到 /storage/emulated/0/${StorageAccess.sharedWorkspaceName()}；" +
                    "若内核正在运行，请到「助手 → DSH 内核」点右上角重启。"
            } else {
                "Android 11 及以上会跳到系统「所有文件访问」页面，打开开关后返回即可；" +
                    "Android 10 及以下直接弹存储权限申请。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = wb.ink3,
        )
    }
}

/** DSH 页顶部的紧凑提示条：未授权时才显示。 */
@Composable
fun AllFilesAccessBanner(modifier: Modifier = Modifier) {
    val wb = LocalWbColors.current
    val (granted, request) = rememberAllFilesAccess()
    if (granted) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "未授予「所有文件访问」，DSH 无法检索手机文件",
            style = MaterialTheme.typography.labelSmall,
            color = wb.warn,
            modifier = Modifier.weight(1f),
        )
        WbButton("去授权", onClick = { request() })
    }
}
