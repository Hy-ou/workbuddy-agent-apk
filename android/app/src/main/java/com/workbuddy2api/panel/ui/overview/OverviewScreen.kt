package com.workbuddy2api.panel.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workbuddy2api.panel.data.BulkAction
import com.workbuddy2api.panel.data.LogEntry
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.ConfirmDialog
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.WbPullRefresh
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberPollingLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.uptimeText
import kotlinx.coroutines.launch

/**
 * 总览：手机端首屏。
 *
 * 一屏内回答三件事：池子还能用吗、钱还够吗、哪里出问题了。
 * 数据 5 秒轮询一次（与 Web 面板同频），下拉可手动刷新。
 */
@Composable
fun OverviewScreen(
    repo: WbRepository,
    onGoAccounts: () -> Unit,
    onGoLogs: () -> Unit,
) {
    val wb = LocalWbColors.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    val overview = rememberPollingLoader(5_000) { repo.overview() }
    val logsLoader = rememberPollingLoader(8_000) { repo.logs() }

    var pending by remember { mutableStateOf<BulkAction?>(null) }
    var running by remember { mutableStateOf(false) }

    val logs: List<LogEntry> = (logsLoader.state as? Loadable.Ready)?.data ?: emptyList()
    val o = (overview.state as? Loadable.Ready)?.data
    val subtitle = o?.let { "v${it.version.ifBlank { "?" }} · 运行 ${uptimeText(it.uptimeSec)}" } ?: "连接中…"

    Column(Modifier.fillMaxSize()) {
        WbTopBar(
            title = "总览",
            subtitle = subtitle,
            actions = {
                IconButton(onClick = { overview.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = wb.ink2)
                }
            },
        )
        when (val st = overview.state) {
            is Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBox(st.message) { overview.refresh() }
            is Loadable.Ready -> {
                WbPullRefresh(refreshing = overview.refreshing, onRefresh = { overview.refresh() }) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item { PoolSummaryCard(st.data) }
                        item { TotalCreditsCard(st.data.accounts) }
                        item {
                            QuickActionChips { label ->
                                pending = when (label) {
                                    "全部签到" -> BulkAction.CHECKIN_ALL
                                    "刷新余额" -> BulkAction.BALANCE_ALL
                                    "全部旅行" -> BulkAction.TRAVEL_ALL
                                    "全部活动" -> BulkAction.ACTIVITY_ALL
                                    else -> BulkAction.KEEPALIVE_ALL
                                }
                            }
                        }
                        if (!st.data.modelLocks.isNullOrEmpty()) {
                            item { ModelLocksSection(st.data.modelLocks.orEmpty()) }
                        }
                        item { HealthSection(st.data.accounts, onGoAccounts) }
                        item { RecentLogsSection(logs, onGoLogs) }
                        item { ServerInfoCard(st.data) }
                        item { Spacer(Modifier.height(28.dp)) }
                    }
                }
            }
        }
    }

    val action = pending
    if (action != null) {
        ConfirmDialog(
            title = action.label,
            text = if (action == BulkAction.BALANCE_ALL) {
                "将逐个账号查询最新余额，账号多时可能需要十几秒。"
            } else {
                "该操作会立即在服务端后台开始执行，结果可在日志中查看。"
            },
            confirmText = if (running) "执行中…" else "开始执行",
            onConfirm = {
                if (running) return@ConfirmDialog
                running = true
                pending = null
                scope.launch {
                    try {
                        val res = repo.bulkAction(action)
                        snackbar.showSnackbar(
                            res.message.ifBlank {
                                if (res.started) "${action.label}已开始，稍后查看日志" else "${action.label}完成"
                            },
                        )
                        overview.refresh(quiet = true)
                        logsLoader.refresh(quiet = true)
                    } catch (e: Exception) {
                        snackbar.showSnackbar(e.message ?: "操作失败")
                    } finally {
                        running = false
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }
}
