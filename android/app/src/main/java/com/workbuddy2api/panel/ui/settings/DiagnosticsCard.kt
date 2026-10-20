package com.workbuddy2api.panel.ui.settings

import android.content.Intent
import android.net.Uri
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
import com.workbuddy2api.panel.BuildConfig
import com.workbuddy2api.panel.dsh.DshManager
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.DeviceEnv
import com.workbuddy2api.panel.util.UpdateChecker
import kotlinx.coroutines.launch

/**
 * 运行体检：把散落在系统设置里的关键授权与运行环境集中展示、一键跳转。
 *
 * 覆盖：电池优化白名单、WebView 内核版本、ABI 支持、DSH 内核树位置、版本更新检查。
 */
@Composable
fun DiagnosticsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wb = LocalWbColors.current
    val scope = rememberCoroutineScope()

    var batteryOk by remember { mutableStateOf(DeviceEnv.isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOk = DeviceEnv.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val chromeMajor = remember {
        runCatching {
            val wv = android.webkit.WebView(context)
            val major = DeviceEnv.chromeMajor(wv.settings.userAgentString)
            runCatching { wv.destroy() }
            major
        }.getOrDefault(0)
    }
    val webViewVersion = if (chromeMajor > 0) "Chromium $chromeMajor" else "未知"
    val abiSupported = remember { DeviceEnv.supportsDsh() }
    val kernelPath = remember { runCatching { DshManager.get(context).kernelRootPath() }.getOrDefault("") }
    val onShared = remember { runCatching { DshManager.get(context).kernelOnSharedStorage() }.getOrDefault(false) }

    var updateState by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var latestUrl by remember { mutableStateOf<String?>(null) }

    WbCard(modifier.fillMaxWidth()) {
        Text("运行体检", style = MaterialTheme.typography.titleSmall, color = wb.ink)
        Text(
            "电池白名单、WebView 内核、架构与内核位置；DSH 依赖这些环境才能稳定运行。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        Spacer(Modifier.height(10.dp))

        // 电池优化
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("忽略电池优化", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
                Text(
                    if (batteryOk) "已加入白名单：后台/锁屏时内核不易被杀" else "未加入：切后台后内核可能被系统回收",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (batteryOk) wb.ok else wb.ink3,
                )
            }
            if (batteryOk) {
                StatusChip("已完成", Tone.Ok)
            } else {
                WbButton("去设置", onClick = { DeviceEnv.requestIgnoreBatteryOptimization(context) })
            }
        }
        Spacer(Modifier.height(10.dp))

        // WebView / ABI
        InfoLine(
            title = "WebView 内核",
            value = webViewVersion + if (chromeMajor in 1 until DeviceEnv.MIN_CHROME_FOR_DSH) "（偏旧，建议升级）" else "",
            ok = chromeMajor >= DeviceEnv.MIN_CHROME_FOR_DSH,
        )
        InfoLine(
            title = "设备架构",
            value = if (abiSupported) "arm64（DSH 可用）" else "${DeviceEnv.abiSummary()}（DSH 不可用，网关/原生助手可用）",
            ok = abiSupported,
        )
        InfoLine(
            title = "DSH 内核树",
            value = if (kernelPath.isBlank()) "未初始化" else (if (onShared) "外置存储 · " else "应用内部 · ") + kernelPath,
            ok = kernelPath.isNotBlank(),
        )
        Spacer(Modifier.height(10.dp))

        // 更新检查
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("版本更新", style = MaterialTheme.typography.bodyMedium, color = wb.ink)
                Text(
                    "当前 ${BuildConfig.VERSION_NAME}" + (if (updateState.isBlank()) "" else " · $updateState"),
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink3,
                )
            }
            if (latestUrl != null) {
                WbButton("去下载", onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latestUrl)))
                    }
                }, primary = true)
            } else {
                WbButton(
                    if (checking) "检查中…" else "检查更新",
                    onClick = {
                        if (checking) return@WbButton
                        checking = true
                        updateState = ""
                        scope.launch {
                            val result = runCatching { UpdateChecker.fetchLatest().getOrThrow() }
                            checking = false
                            result.onSuccess { rel ->
                                if (UpdateChecker.isNewer(rel.tag, BuildConfig.VERSION_NAME)) {
                                    updateState = "发现新版本 ${rel.tag}"
                                    latestUrl = rel.url
                                } else {
                                    updateState = "已是最新"
                                }
                            }.onFailure { e ->
                                updateState = "检查失败（${e.message ?: "网络异常"}）"
                            }
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Mono("${UpdateChecker.REPO} · releases/latest")
    }
}

@Composable
private fun InfoLine(title: String, value: String, ok: Boolean) {
    val wb = LocalWbColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = wb.ink)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) wb.ink3 else wb.warn,
            )
        }
        StatusChip(if (ok) "正常" else "注意", if (ok) Tone.Ok else Tone.Warn)
    }
}
