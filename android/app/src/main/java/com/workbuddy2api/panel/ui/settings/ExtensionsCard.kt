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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    var shizukuAvailable by remember { mutableStateOf(ShizukuShell.available()) }
    var shizukuGranted by remember { mutableStateOf(ShizukuShell.granted()) }
    var notifyGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var copied by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var npmOk by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

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

    androidx.compose.runtime.LaunchedEffect(tick) {
        npmOk = runCatching { manager.npmAvailable() }.getOrDefault(false)
    }

    val toolchain = remember(tick) {
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
                val installed = runCatching { manager.pluginInstalled(p.packageName) }.getOrDefault(false)
                WbButton(
                    text = when {
                        busy == p.packageName -> "处理中…"
                        installed -> "卸载"
                        else -> "安装"
                    },
                    onClick = {
                        if (busy.isNotBlank()) return@WbButton
                        busy = p.packageName
                        scope.launch {
                            val r = if (installed) manager.uninstallPlugin(p.packageName)
                            else manager.installPlugin(p.packageName)
                            note = if (r.ok) {
                                "${p.packageName} 已${if (installed) "卸载" else "安装"}，重启内核后生效"
                            } else {
                                "失败：${r.output.take(160)}"
                            }
                            busy = ""
                            tick++
                        }
                    },
                    primary = !installed,
                )
                WbButton(if (copied == p.packageName) "已复制" else "复制包名", onClick = {
                    clipboard.setText(AnnotatedString(p.packageName))
                    copied = p.packageName
                })
            }
        }
        Spacer(Modifier.height(12.dp))

        // 扩展中心（一）：Termux 系统工具链
        Text("系统工具链（Termux）", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
        Text(
            "Python / Go / Rust / Clang / JDK / Git 等 19 项。安装时自动解析依赖闭包、校验 SHA-256，" +
                "并对每个 ELF 重写 RUNPATH 完成重定位；装好后内核 PATH 直接可用。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        val toolItems = com.workbuddy2api.panel.dsh.Toolchain.CATALOG
        toolItems.forEach { item ->
            val installed = runCatching { manager.toolchainInstalled(item.id) }.getOrDefault(false)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodySmall, color = wb.ink)
                    Text(
                        if (busy == "tc:" + item.id) note.ifBlank { item.desc } else item.desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (busy == "tc:" + item.id) wb.warn else wb.ink3,
                    )
                }
                WbButton(
                    text = when {
                        busy == "tc:" + item.id -> "安装中…"
                        installed -> "卸载"
                        else -> "安装"
                    },
                    onClick = {
                        if (busy.isNotBlank()) return@WbButton
                        if (installed) {
                            val r = manager.uninstallToolchain(item.id)
                            note = r.message
                            tick++
                        } else {
                            busy = "tc:" + item.id
                            note = "准备安装 " + item.label + "…"
                            scope.launch {
                                val r = manager.installToolchain(item.id) { line -> note = line }
                                note = if (r.ok) r.message else "失败：" + r.message
                                busy = ""
                                tick++
                            }
                        }
                    },
                    primary = !installed,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 扩展中心（二）：用内置 npm 把命令行工具装到内核 PATH
        Text("扩展中心（CLI 工具）", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
        Text(
            if (npmOk) "用内置 npm 装到 home/tools，其 bin 已加入内核 PATH，Agent 可直接调用。"
            else "内核尚未内置 npm（需 3.6.0+ 内核资产），安装不可用。",
            style = MaterialTheme.typography.bodySmall,
            color = if (npmOk) wb.ink3 else wb.warn,
        )
        val tools = listOf(
            "typescript" to "tsc 编译器",
            "tsx" to "直接运行 TypeScript",
            "prettier" to "代码格式化",
            "eslint" to "JS/TS 静态检查",
            "http-server" to "静态文件服务器",
            "nodemon" to "文件变更自动重启",
        )
        tools.forEach { (pkg, desc) ->
            val installed = runCatching { manager.toolInstalled(pkg) }.getOrDefault(false)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pkg, style = MaterialTheme.typography.bodySmall, color = wb.ink)
                    Text(desc, style = MaterialTheme.typography.labelSmall, color = wb.ink3)
                }
                WbButton(
                    text = when {
                        busy == "tool:$pkg" -> "处理中…"
                        installed -> "卸载"
                        else -> "安装"
                    },
                    onClick = {
                        if (busy.isNotBlank() || !npmOk) return@WbButton
                        busy = "tool:$pkg"
                        scope.launch {
                            val r = if (installed) manager.uninstallTool(pkg) else manager.installTool(pkg)
                            note = if (r.ok) "$pkg 已${if (installed) "卸载" else "安装"}"
                            else "失败：${r.output.take(160)}"
                            busy = ""
                            tick++
                        }
                    },
                    primary = !installed,
                )
            }
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = wb.ok)
        }

        Spacer(Modifier.height(6.dp))
        Mono("来源: Soodok/Deepseek-Harness-Local-Android 的社区插件清单")
    }
}
