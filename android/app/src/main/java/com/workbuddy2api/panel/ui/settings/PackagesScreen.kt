package com.workbuddy2api.panel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbuddy2api.panel.data.CreditPackage
import com.workbuddy2api.panel.data.PackageAccount
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.EmptyState
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.RealmBadge
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbProgress
import com.workbuddy2api.panel.ui.components.WbPullRefresh
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbDimens
import com.workbuddy2api.panel.ui.theme.WbMono
import com.workbuddy2api.panel.util.creditsText
import com.workbuddy2api.panel.util.parseIso
import com.workbuddy2api.panel.util.percentText
import com.workbuddy2api.panel.util.shortUid
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 折叠状态下每个账号最多展示的套餐条数。 */
private const val COLLAPSED_PACKAGES = 5

private val PACKAGE_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

/** 套餐到期时间：解析失败显示 `—`（Go 零值时间同样按缺失处理）。 */
private fun packageEndText(s: String): String {
    val instant = parseIso(s) ?: return "—"
    return PACKAGE_DATE.format(instant)
}

/**
 * 积分套餐页：按账号列出积分余额与套餐明细。
 *
 * 套餐明细默认只展示前 5 条（手机单列不宜过长），可展开全部；
 * 单个账号拉取失败只影响该卡片（服务端在 `error` 字段里给出原因）。
 */
@Composable
fun PackagesScreen(repo: WbRepository, onBack: () -> Unit) {
    val colors = LocalWbColors.current
    val loader = rememberLoader(repo) { repo.packages() }
    val state = loader.state
    val accounts = (state as? Loadable.Ready<List<PackageAccount>>)?.data

    // 展开状态按账号 key 保存：切 Tab / 重进页面会重置，属于可接受的轻量状态。
    var expandedKeys by remember { mutableStateOf(emptySet<String>()) }

    Column(modifier = Modifier.fillMaxSize()) {
        WbTopBar(
            title = "积分套餐",
            subtitle = repo.serverLabel,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = { if (!loader.refreshing) loader.refresh() },
                    enabled = !loader.refreshing,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新套餐",
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
                    accounts == null -> item { LoadingBox() }
                    accounts.isEmpty() -> item {
                        EmptyState(
                            title = "暂无套餐数据",
                            subtitle = "服务器还没有返回任何账号的套餐信息",
                        )
                    }
                    else -> items(accounts) { account ->
                        val key = accountKey(account)
                        PackageAccountCard(
                            account = account,
                            expanded = expandedKeys.contains(key),
                            onToggle = {
                                expandedKeys = if (expandedKeys.contains(key)) {
                                    expandedKeys - key
                                } else {
                                    expandedKeys + key
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 账号唯一 key：优先 uid，缺失时退化为昵称。 */
private fun accountKey(account: PackageAccount): String =
    account.uid.ifBlank { account.nickname.ifBlank { "unknown" } }

/** 单个账号的套餐卡片：昵称 + 区服 + 剩余/总量 + 进度条 + 套餐明细。 */
@Composable
private fun PackageAccountCard(
    account: PackageAccount,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalWbColors.current
    val packages = account.packages.orEmpty()
    val shown = if (expanded) packages else packages.take(COLLAPSED_PACKAGES)
    val hasQuota = account.size > 0.0
    val ratio = if (hasQuota) (account.remain / account.size).toFloat().coerceIn(0f, 1f) else 0f
    val tone = when {
        !hasQuota -> Tone.Neutral
        ratio >= 0.5f -> Tone.Ok
        ratio >= 0.15f -> Tone.Accent
        else -> Tone.Warn
    }

    WbCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = account.nickname.ifBlank { shortUid(account.uid) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            RealmBadge(account.realm)
        }

        if (account.error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = account.error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.warn,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "剩余 " + creditsText(account.remain),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = WbMono,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (hasQuota) "共 " + creditsText(account.size) else "不限额度",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = WbMono),
                color = colors.ink3,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (hasQuota) {
                Text(
                    text = percentText(ratio.toDouble()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = WbMono),
                    color = colors.ink2,
                    maxLines = 1,
                )
            }
        }
        if (hasQuota) {
            Spacer(Modifier.height(6.dp))
            WbProgress(value = ratio, tone = tone)
        }

        if (packages.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (account.error.isBlank()) "暂无套餐明细" else "套餐明细获取失败",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
        } else {
            shown.forEachIndexed { index, item ->
                Spacer(Modifier.height(10.dp))
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.lineSoft),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                PackageRow(item)
            }
            if (packages.size > COLLAPSED_PACKAGES) {
                Spacer(Modifier.height(10.dp))
                WbButton(
                    text = if (expanded) "收起明细" else "展开全部（共 ${packages.size} 个）",
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 套餐明细行：名称 + 子产品 + 剩余/总量/已用 + 到期日。 */
@Composable
private fun PackageRow(item: CreditPackage) {
    val colors = LocalWbColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.name.ifBlank { item.packageCode.ifBlank { "套餐" } },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = creditsText(item.remain) + " / " + creditsText(item.size),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = WbMono),
                color = colors.ink2,
                maxLines = 1,
            )
        }
        if (item.subProductName.isNotBlank() || item.subProductCode.isNotBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = item.subProductName.ifBlank { item.subProductCode },
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "已用 " + creditsText(item.used),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = WbMono),
                color = colors.ink3,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "到期 " + packageEndText(item.endTime),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = WbMono),
                color = colors.ink3,
                maxLines = 1,
            )
        }
    }
}
