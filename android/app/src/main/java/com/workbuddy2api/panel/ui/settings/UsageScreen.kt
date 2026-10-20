package com.workbuddy2api.panel.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbuddy2api.panel.data.UsageAgg
import com.workbuddy2api.panel.data.UsageSnapshot
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.EmptyState
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.components.InfoRow
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.RealmBadge
import com.workbuddy2api.panel.ui.components.SectionHeader
import com.workbuddy2api.panel.ui.components.StatGrid
import com.workbuddy2api.panel.ui.components.StatTileData
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbPullRefresh
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbDimens
import com.workbuddy2api.panel.ui.theme.WbMono
import com.workbuddy2api.panel.util.bytesText
import com.workbuddy2api.panel.util.compactNum
import com.workbuddy2api.panel.util.creditsText
import com.workbuddy2api.panel.util.msText
import com.workbuddy2api.panel.util.parseIso
import com.workbuddy2api.panel.util.percentText
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 统计范围标签；下标与 [USAGE_HOURS] 一一对应。 */
private val USAGE_RANGES = listOf("24小时", "3天", "7天", "30天", "全部")

/** 传给服务端的 hours 参数，0 表示全部历史。 */
private val USAGE_HOURS = listOf(24, 72, 168, 720, 0)

/** 统计窗口用的紧凑时间格式（手机单列，省掉年份）。 */
private val WINDOW_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun windowText(s: String): String {
    val instant = parseIso(s) ?: return ""
    return WINDOW_FMT.format(instant)
}

/**
 * 用量统计页：时间范围筛选 + KPI 网格 + 按模型 / 按账号明细。
 *
 * 数据量较大（`usage` 会读请求归档文件），因此只在切换范围或下拉时请求，不做轮询。
 */
@Composable
fun UsageScreen(repo: WbRepository, onBack: () -> Unit) {
    val colors = LocalWbColors.current
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val loader = rememberLoader(repo, rangeIndex) { repo.usage(USAGE_HOURS[rangeIndex]) }
    val state = loader.state
    val snapshot = (state as? Loadable.Ready<UsageSnapshot>)?.data

    Column(modifier = Modifier.fillMaxSize()) {
        WbTopBar(
            title = "用量统计",
            subtitle = "范围 " + USAGE_RANGES[rangeIndex],
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = { if (!loader.refreshing) loader.refresh() },
                    enabled = !loader.refreshing,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新用量",
                        tint = colors.ink2,
                    )
                }
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WbDimens.screenPadding),
        ) {
            FilterChips(
                labels = USAGE_RANGES,
                selectedIndex = rangeIndex,
                onSelect = { rangeIndex = it },
            )
        }

        WbPullRefresh(
            refreshing = loader.refreshing,
            onRefresh = { loader.refresh(quiet = true) },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = WbDimens.screenPadding,
                    end = WbDimens.screenPadding,
                    top = 4.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(WbDimens.gap),
            ) {
                when {
                    state is Loadable.Failed -> item {
                        ErrorBox(message = state.message, onRetry = { loader.refresh() })
                    }
                    snapshot == null -> item { LoadingBox() }
                    else -> usageContent(snapshot)
                }
            }
        }
    }
}

/** 把一份用量快照铺成 LazyColumn 的若干 item：KPI → 附加指标 → 明细。 */
private fun LazyListScope.usageContent(snapshot: UsageSnapshot) {
    val totals = snapshot.totals
    val hasRequests = totals.requests > 0L
    val rawRate = if (hasRequests) (totals.requests - totals.errors).toDouble() / totals.requests else 0.0
    val rate = rawRate.coerceIn(0.0, 1.0)
    val rateTone = when {
        !hasRequests -> Tone.Neutral
        rate >= 0.98 -> Tone.Ok
        rate >= 0.90 -> Tone.Warn
        else -> Tone.Bad
    }

    item {
        StatGrid(
            items = listOf(
                StatTileData(label = "请求数", value = compactNum(totals.requests)),
                StatTileData(label = "总 Tokens", value = compactNum(totals.totalTokens)),
                StatTileData(label = "输入 Tokens", value = compactNum(totals.promptTokens)),
                StatTileData(label = "输出 Tokens", value = compactNum(totals.completionTokens)),
                StatTileData(
                    label = "错误数",
                    value = compactNum(totals.errors),
                    tone = if (totals.errors > 0L) Tone.Bad else Tone.Neutral,
                ),
                StatTileData(
                    label = "成功率",
                    value = if (hasRequests) percentText(rate) else "—",
                    tone = rateTone,
                ),
                StatTileData(
                    label = "平均延迟",
                    value = msText(totals.avgLatencyMs.toLong()),
                ),
                StatTileData(
                    label = "生成速度",
                    value = if (totals.avgTokensPerSecond > 0.0) compactNum(totals.avgTokensPerSecond) else "—",
                    unit = "tok/s",
                ),
            ),
        )
    }

    item {
        val window = listOf(windowText(snapshot.windowFrom), windowText(snapshot.windowTo))
            .filter { it.isNotBlank() }
            .joinToString(" → ")
        WbCard {
            InfoRow(label = "消耗积分", value = creditsText(totals.credits), mono = true)
            InfoRow(
                label = "缓存命中",
                value = if (totals.cacheHitRate > 0.0) percentText(totals.cacheHitRate) else "—",
                mono = true,
            )
            InfoRow(
                label = "每百万 Token",
                value = if (totals.creditsPer1mTokens > 0.0) {
                    creditsText(totals.creditsPer1mTokens) + " 积分"
                } else {
                    "—"
                },
                mono = true,
            )
            InfoRow(
                label = "统计窗口",
                value = window.ifBlank { "全部历史" },
                mono = true,
            )
            if (snapshot.fileBytes > 0L) {
                InfoRow(label = "归档大小", value = bytesText(snapshot.fileBytes), mono = true)
            }
        }
    }

    val byModel = snapshot.byModel.orEmpty()
        .filter { it.requests > 0L || it.totalTokens > 0L || it.key.isNotBlank() }
        .sortedByDescending { it.totalTokens }
    val byAccount = snapshot.byAccount.orEmpty()
        .filter { it.requests > 0L || it.totalTokens > 0L || it.key.isNotBlank() }
        .sortedByDescending { it.totalTokens }

    if (byModel.isNotEmpty()) {
        item { SectionHeader(title = "按模型", subtitle = "${byModel.size} 个模型") }
        items(byModel) { agg -> UsageAggCard(agg) }
    }

    if (byAccount.isNotEmpty()) {
        item { SectionHeader(title = "按账号", subtitle = "${byAccount.size} 个账号") }
        items(byAccount) { agg -> UsageAggCard(agg) }
    }

    if (byModel.isEmpty() && byAccount.isEmpty()) {
        item {
            EmptyState(
                title = "该范围内没有用量明细",
                subtitle = "换一个时间范围，或等服务端产生请求后再来看",
            )
        }
    }
}

/** 单个模型 / 账号的用量卡片：key + 请求 + Tokens + 积分 + 延迟。 */
@Composable
private fun UsageAggCard(agg: UsageAgg) {
    val colors = LocalWbColors.current
    WbCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = agg.key.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = WbMono,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (agg.realm.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                RealmBadge(agg.realm)
            }
            if (agg.errors > 0L) {
                Spacer(Modifier.width(6.dp))
                StatusChip(text = "${compactNum(agg.errors)} 错误", tone = Tone.Bad)
            }
        }
        if (agg.extra.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = agg.extra,
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UsageMetric(label = "请求", value = compactNum(agg.requests), modifier = Modifier.weight(1f))
            UsageMetric(label = "Tokens", value = compactNum(agg.totalTokens), modifier = Modifier.weight(1f))
            UsageMetric(label = "积分", value = creditsText(agg.credits), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UsageMetric(
                label = "平均延迟",
                value = msText(agg.avgLatencyMs.toLong()),
                modifier = Modifier.weight(1f),
            )
            UsageMetric(
                label = "生成速度",
                value = if (agg.avgTokensPerSecond > 0.0) compactNum(agg.avgTokensPerSecond) else "—",
                modifier = Modifier.weight(1f),
            )
            UsageMetric(
                label = "缓存命中",
                value = if (agg.cacheHitRate > 0.0) percentText(agg.cacheHitRate) else "—",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 卡片内的极小指标：灰色标签 + 等宽数值。 */
@Composable
private fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalWbColors.current
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = WbMono),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
