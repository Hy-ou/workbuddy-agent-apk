package com.workbuddy2api.panel.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.workbuddy2api.panel.data.Account
import com.workbuddy2api.panel.data.LogEntry
import com.workbuddy2api.panel.data.ModelLock
import com.workbuddy2api.panel.data.Overview
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.SectionHeader
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbProgress
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.agoText
import com.workbuddy2api.panel.util.compactNum
import com.workbuddy2api.panel.util.creditsText
import com.workbuddy2api.panel.util.percentText
import com.workbuddy2api.panel.util.remainText
import com.workbuddy2api.panel.util.uptimeText
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 账号状态（移动端统一口径）。 */
internal fun statusLabel(a: Account): Pair<String, Tone> = when {
    a.disabled -> "已禁用" to Tone.Bad
    a.cooling -> "冷却中" to Tone.Warn
    a.paused -> "已暂停" to Tone.Neutral
    else -> "可用" to Tone.Ok
}

internal fun riskScore(a: Account): Int = when {
    a.disabled -> 100
    a.cooling -> 80
    a.paused -> 60
    a.creditsTotal > 0 && a.credits / a.creditsTotal < 0.2 -> 40
    else -> 0
}

@Composable
internal fun PoolSummaryCard(o: Overview) {
    val wb = LocalWbColors.current
    val ratio = if (o.total > 0) o.healthy.toFloat() / o.total else 0f
    WbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("账号池状态", style = MaterialTheme.typography.titleMedium, color = wb.ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    "可用 ${o.healthy} / 共 ${o.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink3,
                )
            }
            Text(
                percentText(ratio * 100.0),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.headlineSmall,
                color = if (ratio >= 0.6f) wb.ok else if (ratio >= 0.3f) wb.warn else wb.bad,
            )
        }
        Spacer(Modifier.height(12.dp))
        WbProgress(ratio, tone = if (ratio >= 0.6f) Tone.Ok else if (ratio >= 0.3f) Tone.Warn else Tone.Bad)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("可用 ${o.healthy}", Tone.Ok)
            StatusChip("冷却 ${o.cooling}", Tone.Warn)
            StatusChip("暂停 ${o.paused}", Tone.Neutral)
            StatusChip("停用 ${o.disabled}", Tone.Bad)
        }
        if (o.inFlightFull > 0) {
            Spacer(Modifier.height(10.dp))
            Text("并发已满：$o.inFlightFull 个账号在途已满，新请求会排队等待", color = wb.warn, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun QuickActionChips(onAction: (String) -> Unit) {
    val wb = LocalWbColors.current
    val items = listOf(
        "全部签到" to Icons.Outlined.VerifiedUser,
        "刷新余额" to Icons.Outlined.Bolt,
        "全部旅行" to Icons.Outlined.Schedule,
        "全部活动" to Icons.Outlined.Bolt,
        "全部保活" to Icons.Outlined.Schedule,
    )
    SectionHeader("快捷操作", subtitle = "异步执行，结果见日志")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(3).forEach { (label, icon) ->
            QuickChip(label, icon, wb.accent, wb.accentSoft) { onAction(label) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.drop(3).forEach { (label, icon) ->
            QuickChip(label, icon, wb.ink2, wb.surface2) { onAction(label) }
        }
    }
}

@Composable
private fun RowScope.QuickChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}

@Composable
internal fun ModelLocksSection(locks: List<ModelLock>) {
    val wb = LocalWbColors.current
    if (locks.isEmpty()) return
    SectionHeader("模型锁池", subtitle = "${locks.size} 个模型受限")
    locks.take(5).forEach { lock ->
        val (label, tone) = when (lock.state) {
            "locked" -> "整池不可用" to Tone.Bad
            "starved" -> "没号可用" to Tone.Warn
            else -> "部分限流" to Tone.Warn
        }
        WbCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lock.model, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall, color = wb.ink)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${if (lock.realm == "global") "国际" else "国内"} · 可服务 ${lock.servable}/${lock.total} · 锁 ${lock.locked}",
                        style = MaterialTheme.typography.bodySmall,
                        color = wb.ink3,
                    )
                }
                StatusChip(label, tone)
            }
        }
    }
}

@Composable
internal fun HealthSection(accounts: List<Account>, onMore: () -> Unit) {
    val wb = LocalWbColors.current
    val risky = accounts.sortedByDescending { riskScore(it) }.take(4)
    SectionHeader(
        "账号健康",
        subtitle = "按风险排序",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部", style = MaterialTheme.typography.labelMedium, color = wb.accent)
                Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, tint = wb.accent, modifier = Modifier.size(12.dp))
            }
        },
    )
    if (risky.isEmpty()) {
        WbCard(Modifier.fillMaxWidth()) {
            Text("还没有账号，先去「账号」页添加", color = wb.ink3, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    WbCard(Modifier.fillMaxWidth(), onClick = onMore) {
        risky.forEachIndexed { index, a ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(wb.lineSoft))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        a.nickname.ifBlank { "未命名" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = wb.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Mono(
                        buildString {
                            append(creditsText(a.credits))
                            if (a.creditsTotal > 0) append(" / ").append(creditsText(a.creditsTotal))
                            if (a.cooling) append(" · ").append(remainText(a.coolRemainingSec))
                        },
                    )
                }
                val (label, tone) = statusLabel(a)
                StatusChip(label, tone)
            }
        }
    }
}

@Composable
internal fun RecentLogsSection(logs: List<LogEntry>, onMore: () -> Unit) {
    val wb = LocalWbColors.current
    SectionHeader(
        "最近日志",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("更多", style = MaterialTheme.typography.labelMedium, color = wb.accent)
                Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, tint = wb.accent, modifier = Modifier.size(12.dp))
            }
        },
    )
    WbCard(Modifier.fillMaxWidth(), onClick = onMore) {
        if (logs.isEmpty()) {
            Text("暂无日志", color = wb.ink3, style = MaterialTheme.typography.bodySmall)
        }
        logs.takeLast(3).forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    runCatching { DateTimeFormatter.ofPattern("HH:mm").format(LocalTime.parse(entry.ts.substring(11, 19))) }
                        .getOrDefault("--:--"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = wb.ink3,
                    modifier = Modifier.width(46.dp),
                )
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.text.contains("error", true) || entry.text.contains("失败")) wb.bad else wb.ink2,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
internal fun ServerInfoCard(o: Overview) {
    val wb = LocalWbColors.current
    WbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("服务信息", style = MaterialTheme.typography.titleSmall, color = wb.ink, modifier = Modifier.weight(1f))
            Mono("v${o.version.ifBlank { "?" }}")
        }
        Spacer(Modifier.height(8.dp))
        InfoLine("运行时长", uptimeText(o.uptimeSec))
        InfoLine("Redis", if (o.redisMode == "upstash") "Upstash（共享状态）" else "未启用（单机）")
        InfoLine("粘性会话", "${o.stickySessions} 个")
        InfoLine("鉴权", if (o.authRequired) "已启用 API Key" else "未启用（开放访问）")
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val wb = LocalWbColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = wb.ink3, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = wb.ink2)
    }
}

@Composable
internal fun TotalCreditsCard(accounts: List<Account>) {
    val wb = LocalWbColors.current
    val totalCredits = accounts.sumOf { it.credits }
    val expiring = accounts.sumOf { it.creditsExpiring }
    WbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("剩余积分（全部账号）", style = MaterialTheme.typography.bodySmall, color = wb.ink3)
                Spacer(Modifier.height(2.dp))
                Text(
                    compactNum(totalCredits),
                    style = MaterialTheme.typography.displaySmall,
                    color = wb.ink,
                )
            }
            if (expiring > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("临期", style = MaterialTheme.typography.labelSmall, color = wb.warn)
                    Text(compactNum(expiring), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium, color = wb.warn)
                }
            }
        }
    }
}
