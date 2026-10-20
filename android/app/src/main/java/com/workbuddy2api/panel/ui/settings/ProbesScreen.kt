package com.workbuddy2api.panel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbuddy2api.panel.data.ModelProbes
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.EmptyState
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.LoadingBox
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
import com.workbuddy2api.panel.util.agoText
import com.workbuddy2api.panel.util.compactNum
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 一条探测记录：模型 id + 原始字段（服务端把每条记录当不透明 JSON 透传）。 */
private class ProbeEntry(
    val model: String,
    val fields: JsonObject?,
    val raw: JsonElement?,
)

/** 从 `probes` 里取出 (模型 -> JSON) 列表；对象和数组两种形态都兼容。 */
private fun probeEntries(element: JsonElement?): List<ProbeEntry> = when (element) {
    is JsonObject -> element.entries
        .map { (key, value) -> ProbeEntry(key, value as? JsonObject, value) }
        .sortedBy { it.model }
    is JsonArray -> element.mapIndexedNotNull { index, value ->
        val obj = value as? JsonObject ?: return@mapIndexedNotNull null
        val name = obj.probeStr("model").ifBlank {
            obj.probeStr("key").ifBlank { obj.probeStr("name").ifBlank { "#" + (index + 1) } }
        }
        ProbeEntry(name, obj, value)
    }
    else -> emptyList()
}

private fun JsonObject.probeStr(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.probeLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

/** 判据中文名：clamped 表示上游静默钳制，at_least 表示至少支持到阶梯上限。 */
private fun verdictLabel(verdict: String): String = when (verdict.trim().lowercase()) {
    "clamped" -> "被截断"
    "at_least" -> "至少"
    "inconclusive" -> "不确定"
    "" -> "未知"
    else -> verdict.trim()
}

private fun verdictTone(verdict: String): Tone = when (verdict.trim().lowercase()) {
    "clamped" -> Tone.Warn
    "at_least" -> Tone.Ok
    else -> Tone.Neutral
}

/**
 * 模型探测页：展示 `scripts/probe_max_tokens.py --panel-out` 生成的实测上限。
 *
 * 服务端把探测文件原样透传（`probes` 是任意 JSON），因此这里逐字段防御式读取，
 * 缺失字段一律显示 `—`，不让一条脏数据拖垮整页。
 */
@Composable
fun ProbesScreen(repo: WbRepository, onBack: () -> Unit) {
    val colors = LocalWbColors.current
    val loader = rememberLoader(repo) { repo.modelProbes() }
    val state = loader.state
    val probes = (state as? Loadable.Ready<ModelProbes>)?.data

    val updated = probes?.updatedAt?.takeIf { it.isNotBlank() }
    val subtitle = if (updated != null) "更新于 " + agoText(updated) else repo.serverLabel

    Column(modifier = Modifier.fillMaxSize()) {
        WbTopBar(
            title = "模型探测",
            subtitle = subtitle,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = { if (!loader.refreshing) loader.refresh() },
                    enabled = !loader.refreshing,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新探测结果",
                        tint = colors.ink2,
                    )
                }
            },
        )

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
                    probes == null -> item { LoadingBox() }
                    else -> probesContent(probes)
                }
            }
        }
    }
}

/** 把探测结果铺成 LazyColumn 的 item；无数据时给出「怎么生成」的指引。 */
private fun LazyListScope.probesContent(probes: ModelProbes) {
    if (!probes.exists) {
        item {
            EmptyState(
                title = "暂无探测数据",
                subtitle = "运行 scripts/probe_max_tokens.py 后生成",
            )
        }
        return
    }
    val entries = probeEntries(probes.probes)
    if (entries.isEmpty()) {
        item {
            EmptyState(
                title = "暂无探测数据",
                subtitle = "探测结果文件存在，但里面没有模型记录",
            )
        }
        return
    }
    item {
        WbCard {
            Text(
                text = "判读方式",
                style = MaterialTheme.typography.titleSmall,
                color = LocalWbColors.current.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "「被截断」表示上游静默钳制，实测值即真实上限；「至少」表示上探到阶梯上限仍未截断；" +
                    "「不确定」表示模型自行收尾、判据不足。",
                style = MaterialTheme.typography.bodySmall,
                color = LocalWbColors.current.ink3,
            )
        }
    }
    items(entries) { entry -> ProbeCard(entry) }
}

/** 单个模型的探测卡片：模型 id + 判据胶囊 + 声称/实测上限 + 备注。 */
@Composable
private fun ProbeCard(entry: ProbeEntry) {
    val colors = LocalWbColors.current
    val fields = entry.fields
    val verdict = fields?.probeStr("verdict").orEmpty()
    val claimed = fields?.probeLong("claimed")
    val measured = fields?.probeLong("measured")
    val note = fields?.probeStr("note").orEmpty()
    val testedAt = fields?.probeStr("tested_at").orEmpty()
    val source = fields?.probeStr("source").orEmpty()

    WbCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.model,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = WbMono,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            StatusChip(text = verdictLabel(verdict), tone = verdictTone(verdict))
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProbeMetric(
                label = "声称上限",
                value = claimed?.let { compactNum(it) } ?: "—",
                modifier = Modifier.weight(1f),
            )
            ProbeMetric(
                label = "实测上限",
                value = measured?.let { compactNum(it) } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }

        if (fields == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.raw?.toString() ?: "—",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = WbMono),
                color = colors.ink2,
            )
        }

        if (note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "判据：$note",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink2,
            )
        }

        val meta = buildList {
            if (testedAt.isNotBlank()) add("测试于 " + agoText(testedAt))
            if (source.isNotBlank()) add(source)
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 卡片内的小指标：灰色标签 + 等宽数值。 */
@Composable
private fun ProbeMetric(label: String, value: String, modifier: Modifier = Modifier) {
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
