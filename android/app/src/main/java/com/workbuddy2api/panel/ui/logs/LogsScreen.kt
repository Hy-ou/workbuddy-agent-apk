package com.workbuddy2api.panel.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.workbuddy2api.panel.data.LogEntry
import com.workbuddy2api.panel.data.RequestLogEntry
import com.workbuddy2api.panel.data.RequestMetrics
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbPullRefresh
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.state.rememberPollingLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.compactNum
import com.workbuddy2api.panel.util.msText
import com.workbuddy2api.panel.util.percentText
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun hhmmss(iso: String): String = runCatching {
    TIME_FMT.format(OffsetDateTime.parse(iso).toLocalTime())
}.getOrDefault("--:--:--")

/**
 * 日志页：运行日志（内存环形缓冲）与请求审计（JSONL 归档）。
 *
 * 手机端一屏只做一件事：要么看实时日志，要么查请求；用顶部分段切换。
 */
@Composable
fun LogsScreen(repo: WbRepository) {
    val wb = LocalWbColors.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        WbTopBar(
            title = "日志",
            subtitle = if (tab == 0) "实时运行日志" else "请求审计与指标",
            actions = {
                IconButton(onClick = { tick++ }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = wb.ink2)
                }
            },
        )
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilterChips(listOf("运行日志", "请求日志"), tab) { tab = it }
        }
        if (tab == 0) RuntimeLogTab(repo, tick) else RequestLogTab(repo, tick)
    }
}

@Composable
private fun RuntimeLogTab(repo: WbRepository, tick: Int) {
    val wb = LocalWbColors.current
    val snackbar = LocalSnackbar.current
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var channel by rememberSaveable { mutableIntStateOf(0) }
    var autoRefresh by rememberSaveable { mutableStateOf(true) }
    val loader = rememberPollingLoader(5_000, autoRefresh, tick) { repo.logs() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                FilterChips(listOf("全部", "任务", "对话", "系统"), channel) { channel = it }
            }
            Text("自动刷新", style = MaterialTheme.typography.labelSmall, color = wb.ink3)
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = autoRefresh,
                onCheckedChange = { autoRefresh = it },
                modifier = Modifier.size(width = 40.dp, height = 24.dp),
            )
        }

        when (val st = loader.state) {
            is Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBox(st.message) { loader.refresh() }
            is Loadable.Ready -> {
                val channelKey = when (channel) {
                    1 -> "task"
                    2 -> "chat"
                    3 -> "sys"
                    else -> null
                }
                val rows = st.data.filter { channelKey == null || it.ch == channelKey }.reversed()
                WbPullRefresh(refreshing = loader.refreshing, onRefresh = { loader.refresh() }) {
                    if (rows.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无日志", color = wb.ink3, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(rows) { entry ->
                                LogRow(entry) {
                                    clipboard.setText(AnnotatedString(entry.text))
                                    scope.launch { snackbar.showSnackbar("已复制日志") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, onCopy: () -> Unit) {
    val wb = LocalWbColors.current
    val tone = when (entry.ch) {
        "task" -> wb.accent
        "chat" -> wb.ok
        else -> wb.ink3
    }
    val label = when (entry.ch) {
        "task" -> "任务"
        "chat" -> "对话"
        else -> "系统"
    }
    val textColor = when {
        Regex("error|失败|错误", RegexOption.IGNORE_CASE).containsMatchIn(entry.text) -> wb.bad
        Regex("warn|冷却|熔断", RegexOption.IGNORE_CASE).containsMatchIn(entry.text) -> wb.warn
        else -> wb.ink2
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onCopy)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(
            hhmmss(entry.ts),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = wb.ink3,
            modifier = Modifier.width(62.dp),
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(tone.copy(alpha = 0.14f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(label, color = tone, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RequestLogTab(repo: WbRepository, tick: Int) {
    val wb = LocalWbColors.current
    var outcome by rememberSaveable { mutableIntStateOf(0) }
    val outcomeKeys = remember { listOf(null, "success", "http_error", "stream_error", "interrupted") }
    val metrics = rememberPollingLoader(5_000, true, tick) { repo.requestMetrics() }
    val rows = rememberLoader(outcome, tick) { repo.requestLogs(limit = 200, outcome = outcomeKeys[outcome]) }

    Column(Modifier.fillMaxSize()) {
        val m = (metrics.state as? Loadable.Ready)?.data
        if (m != null) {
            WbCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("完成", compactNum(m.completed), wb.ink)
                    MetricItem("成功率", percentText(m.successRate), if (m.successRate >= 90) wb.ok else wb.warn)
                    MetricItem("平均耗时", msText(m.avgDurationMs.toLong()), wb.ink2)
                    MetricItem("在途", m.inFlight.toString(), if (m.inFlight > 0) wb.accent else wb.ink3)
                }
            }
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            FilterChips(listOf("全部", "成功", "HTTP 错误", "流错误", "中断"), outcome) { outcome = it }
        }
        when (val st = rows.state) {
            is Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBox(st.message) { rows.refresh() }
            is Loadable.Ready -> {
                WbPullRefresh(refreshing = rows.refreshing, onRefresh = { rows.refresh() }) {
                    if (st.data.entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无请求记录", color = wb.ink3, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(st.data.entries) { e -> RequestRow(e) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val wb = LocalWbColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, color = color)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = wb.ink3)
    }
}

@Composable
private fun RequestRow(e: RequestLogEntry) {
    val wb = LocalWbColors.current
    val ok = e.ok && e.status in 200..299
    val color = if (ok) wb.ok else wb.bad
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(wb.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hhmmss(e.time),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = wb.ink3,
                modifier = Modifier.width(62.dp),
            )
            Text(
                "${e.status}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier.width(38.dp),
            )
            Text(
                e.model.ifBlank { e.path },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = wb.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                msText(e.durationMs),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = wb.ink2,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                e.account.ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = wb.ink3,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                "${compactNum(e.promptTokens)}→${compactNum(e.completionTokens)} · 共 ${compactNum(e.totalTokens)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = wb.ink3,
            )
        }
        if (e.clientIp.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "${e.outcome.ifBlank { "—" }} · ${e.clientIp} · 尝试 ${e.attempts} 次",
                style = MaterialTheme.typography.labelSmall,
                color = wb.ink3,
            )
        }
    }
}
