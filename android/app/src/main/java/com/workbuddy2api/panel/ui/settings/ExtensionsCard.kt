package com.workbuddy2api.panel.ui.settings

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.workbuddy2api.panel.dsh.DshManager
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.ShizukuShell
import java.io.File

/**
 * 扩展能力：通知 / Shizuku 特权 / 工具链状态 / 社区插件目录。
 *
 * - 通知：AI 用 `notify` 工具推任务完成提醒（只需通知权限）。
 * - Shizuku：用户安装并授权后，AI 的 `shell` 工具可装应用、模拟输入、截屏、改系统设置。
 * - 社区插件：精选 dsh 生态插件，可在 DSH 控制台插件面板安装（需内核内置 npm/pnpm）。
 */
@Composable
fun ExtensionsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wb = LocalWbColors.current
    val clipboard = LocalClipboardManager.current
    val manager = remember { DshManager.get(context) }

    var shizukuAvailable by remember { mutableStateOf(ShizukuShell.available()) }
    var shizukuGranted by remember { mutableStateOf(ShizukuShell.granted()) }
    var notifyGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var copied by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shizukuAvailable = ShizukuShell.available()
                shizukuGranted = ShizukuShell.granted()
                notifyGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toolchain = remember {
        runCatching {
            val kernel = File(manager.kernelRootPath())
            listOf(
                "node 运行时" to File(context.applicationInfo.nativeLibraryDir, "libdshnode.so").isFile,
                "npm" to File(kernel, "bin/npm").exists(),
                "git" to File(kernel, "bin/git").exists(),
                "python" to File(kernel, "bin/python3").exists(),
            )
        }.getOrDefault(emptyList())
    }

    WbCard(modifier.fillMaxWidth()) {
        Text("扩展能力", style = MaterialTheme.typography.titleSmall, color = wb.ink)
        Text(
            "通知推送、Shizuku 系统特权、工具链与社区插件。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        Spacer(Modifier.height(10.dp))

        // 通知
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 通知", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
                Text(
                    "任务完成或需要你关注时推送到通知栏（AI 的 notify 工具）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink3,
                )
            }
            StatusChip(if (notifyGranted) "已允许" else "未授权", if (notifyGranted) Tone.Ok else Tone.Warn)
        }
        Spacer(Modifier.height(10.dp))

        // Shizuku
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shizuku 系统特权", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
                Text(
                    when {
                        !shizukuAvailable -> "未检测到 Shizuku：安装并启动后，AI 可装应用/模拟输入/截屏/改设置"
                        !shizukuGranted -> "已检测到 Shizuku，尚未授权"
                        else -> "已授权：AI 的 shell 工具可执行系统命令"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (shizukuGranted) wb.ok else wb.ink3,
                )
            }
            when {
                !shizukuAvailable -> StatusChip("未安装", Tone.Neutral)
                !shizukuGranted -> WbButton("申请授权", onClick = {
                    runCatching { ShizukuShell.requestPermission() }
                    shizukuGranted = ShizukuShell.granted()
                })
                else -> StatusChip("已授权", Tone.Ok)
            }
        }
        Spacer(Modifier.height(10.dp))

        // 工具链
        Text("工具链", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
        Text(
            "内置运行时可直接跑 Agent 命令；npm/git/python 未随精简包内置，社区插件安装需要它们。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        toolchain.forEach { (name, ok) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodySmall, color = wb.ink, modifier = Modifier.weight(1f))
                StatusChip(if (ok) "内置" else "未内置", if (ok) Tone.Ok else Tone.Neutral)
            }
        }
        Spacer(Modifier.height(10.dp))

        // 社区插件
        Text("社区插件", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
        Text(
            "精选 dsh 生态插件；点「复制包名」后到 DSH 控制台 → 插件 → 添加插件里粘贴安装。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        val plugins = remember { com.workbuddy2api.panel.dsh.CommunityPlugins.CATALOG }
        plugins.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.id, style = MaterialTheme.typography.bodySmall, color = wb.ink)
                    Text(p.desc.take(48), style = MaterialTheme.typography.labelSmall, color = wb.ink3)
                }
                WbButton(if (copied == p.packageName) "已复制" else "复制包名", onClick = {
                    clipboard.setText(AnnotatedString(p.packageName))
                    copied = p.packageName
                })
            }
        }
        Spacer(Modifier.height(6.dp))
        Mono("来源: Soodok/Deepseek-Harness-Local-Android 的社区插件清单")
    }
}
