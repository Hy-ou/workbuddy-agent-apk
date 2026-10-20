package com.workbuddy2api.panel.ui.settings

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbuddy2api.panel.dsh.DshManager
import com.workbuddy2api.panel.dsh.DshManager.State
import com.workbuddy2api.panel.dsh.DshPlugins
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import kotlinx.coroutines.launch

/**
 * DSH 工具：插件开关、安全模式、备份导出/导入、我的补丁。
 *
 * 修改插件开关 / 清空补丁后需要重启内核生效（卡片里有提示）。
 */
@Composable
fun DshToolsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wb = LocalWbColors.current
    val scope = rememberCoroutineScope()
    val manager = remember { DshManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()

    var plugins by remember { mutableStateOf(emptyList<DshPlugins.Plugin>()) }
    var pluginsLoaded by remember { mutableStateOf(false) }
    var patchCount by remember { mutableIntStateOf(0) }
    var failures by remember { mutableStateOf(0 to "") }
    var note by remember { mutableStateOf("") }

    fun reload() {
        plugins = runCatching { manager.plugins() }.getOrDefault(emptyList())
        pluginsLoaded = true
        patchCount = runCatching { manager.myPatchCount() }.getOrDefault(0)
        failures = runCatching { manager.failureInfo() }.getOrDefault(0 to "")
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                manager.exportBackup(uri)
                    .onSuccess { note = "已导出 ${it.files} 个文件（${it.bytes / 1024 / 1024} MB）" }
                    .onFailure { note = "导出失败：${it.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                manager.importBackup(uri)
                    .onSuccess { note = "已导入 ${it.files} 个文件（原数据备份在 ${it.backupDir}）" }
                    .onFailure { note = "导入失败：${it.message}" }
                reload()
            }
        }
    }

    WbCard(modifier.fillMaxWidth()) {
        Text("DSH 工具", style = MaterialTheme.typography.titleSmall, color = wb.ink)
        Text(
            "插件开关、安全模式、备份与「我的补丁」。改动插件或清空补丁后需重启内核。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        Spacer(Modifier.height(10.dp))

        // ── 插件开关 ──
        Text("插件", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
        if (!pluginsLoaded) {
            Text("正在扫描内核插件…", style = MaterialTheme.typography.bodySmall, color = wb.ink3)
        } else if (plugins.isEmpty()) {
            Text("未发现可管理的插件（内核树可能尚未解压）", style = MaterialTheme.typography.bodySmall, color = wb.ink3)
        } else {
            plugins.take(60).forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.id, style = MaterialTheme.typography.bodySmall, color = wb.ink)
                        if (p.builtinDisabled) {
                            Text(
                                "内置禁用（${p.packageName}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = wb.ink3,
                            )
                        } else if (p.description.isNotBlank()) {
                            Text(
                                p.description.take(60),
                                style = MaterialTheme.typography.labelSmall,
                                color = wb.ink3,
                            )
                        }
                    }
                    Switch(
                        checked = !p.disabled,
                        enabled = !p.builtinDisabled,
                        onCheckedChange = { enabled ->
                            manager.setPluginDisabled(p.id, !enabled)
                            plugins = manager.plugins()
                            note = "插件 ${p.id} 已${if (enabled) "启用" else "禁用"}，重启内核后生效"
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 安全模式 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("安全模式", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
                Text(
                    if (failures.first > 0) "连续启动失败 ${failures.first} 次，可用出厂配置启动（保留会话/凭证）"
                    else "插件配置被改坏导致起不来时，用出厂 profile 启动",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failures.first > 0) wb.warn else wb.ink3,
                )
            }
            WbButton(
                "以安全模式启动",
                onClick = {
                    scope.launch {
                        note = "正在以安全模式启动…"
                        val r = manager.startSafeMode()
                        note = when (r) {
                            is State.Running -> "安全模式启动成功"
                            is State.Failed -> "安全模式启动失败：${r.message.take(120)}"
                            else -> "安全模式启动中"
                        }
                        reload()
                    }
                },
                primary = failures.first > 0,
            )
        }
        Spacer(Modifier.height(10.dp))

        // ── 备份 ──
        Text("备份 / 还原", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
        Text(
            "备份含 dshhome（会话/插件/凭证）、原生助手会话、网关配置与我的补丁；请妥善保管。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WbButton(
                "导出备份",
                onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                        .format(java.util.Date())
                    exportLauncher.launch("WorkBuddy-backup-$stamp.zip")
                },
                modifier = Modifier.weight(1f),
            )
            WbButton(
                "导入备份",
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))

        // ── 我的补丁 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("我的补丁", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
                Text(
                    if (patchCount > 0) "已保存 $patchCount 个改动文件，启动时自动回放"
                    else "把对内核文件的修改保存下来，重新解压/升级后自动回放",
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink3,
                )
            }
            StatusChip(if (patchCount > 0) "$patchCount 个" else "无", if (patchCount > 0) Tone.Ok else Tone.Neutral)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WbButton(
                "保存修改为补丁",
                onClick = {
                    val n = runCatching { manager.saveMyPatches() }.getOrDefault(0)
                    patchCount = n
                    note = if (n > 0) "已保存 $n 个改动文件" else "没有检测到与出厂不同的文件"
                    reload()
                },
                modifier = Modifier.weight(1f),
            )
            WbButton(
                "清空补丁",
                onClick = {
                    manager.clearMyPatches()
                    patchCount = 0
                    note = "已清空补丁，下次启动将重新解压出厂内核树"
                    reload()
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (note.isNotBlank()) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = wb.ok)
        }
        Spacer(Modifier.height(6.dp))
        Mono("内核树: ${runCatching { manager.kernelRootPath() }.getOrDefault("")}")
        when (val s = state) {
            is State.Running -> Mono("当前状态: 运行中")
            is State.Failed -> Mono("当前状态: 启动失败 · ${s.message.take(80)}")
            else -> Mono("当前状态: 未运行")
        }
    }
}
