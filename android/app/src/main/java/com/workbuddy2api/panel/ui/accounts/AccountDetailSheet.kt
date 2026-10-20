package com.workbuddy2api.panel.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy2api.panel.data.Account
import com.workbuddy2api.panel.data.AccountAction
import com.workbuddy2api.panel.data.ActionResult
import com.workbuddy2api.panel.data.BulkAction
import com.workbuddy2api.panel.data.WbHttpException
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.ConfirmDialog
import com.workbuddy2api.panel.ui.components.InfoRow
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.RealmBadge
import com.workbuddy2api.panel.ui.components.SectionHeader
import com.workbuddy2api.panel.ui.components.StatGrid
import com.workbuddy2api.panel.ui.components.StatTileData
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.agoText
import com.workbuddy2api.panel.util.compactNum
import com.workbuddy2api.panel.util.creditsText
import com.workbuddy2api.panel.util.msText
import com.workbuddy2api.panel.util.remainText
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/** 操作网格中的一项。 */
private data class AcctActionItem(
    val label: String,
    val action: AccountAction,
    val tone: Tone = Tone.Neutral,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/**
 * 账号详情底部弹层：关键指标 + 状态明细 + 模型成本 / Token 用量 + 运维动作。
 *
 * 动作执行期间禁用全部按钮并在按钮区显示进度；移除需要二次确认，成功后关闭弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailSheet(
    account: Account,
    repo: WbRepository,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val colors = LocalWbColors.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var running by remember { mutableStateOf<AccountAction?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    /** 执行单个账号动作；成功后提示并刷新列表，移除则关闭弹层。 */
    fun run(action: AccountAction) {
        if (running != null) return
        running = action
        scope.launch {
            try {
                val result = repo.accountAction(account.uid, action)
                snackbar.showSnackbar(acctActionResultMessage(result, action))
                onChanged()
                if (action == AccountAction.REMOVE) onDismiss()
            } catch (e: Exception) {
                snackbar.showSnackbar(acctErrorMessage(e))
            } finally {
                running = null
            }
        }
    }

    val state = acctState(account)
    val actions = buildList {
        if (!account.enterprise) {
            add(
                AcctActionItem(
                    label = if (account.checkinDone) "已签到" else "签到",
                    action = AccountAction.CHECKIN,
                    enabled = !account.checkinDone,
                ),
            )
        }
        add(
            AcctActionItem(
                label = if (account.enterprise) "刷新额度" else "刷新余额",
                action = AccountAction.BALANCE,
            ),
        )
        if (account.paused) {
            add(AcctActionItem("恢复选号", AccountAction.RESUME))
        } else {
            add(AcctActionItem("暂停选号", AccountAction.PAUSE))
        }
        if (!account.disabled) {
            add(AcctActionItem("禁用", AccountAction.DISABLE, tone = Tone.Bad))
        }
        if (account.cooling || account.disabled || account.paused) {
            add(AcctActionItem("解冻", AccountAction.REVIVE, primary = true))
        }
        add(
            AcctActionItem(
                label = "移除",
                action = AccountAction.REMOVE,
                tone = Tone.Bad,
                destructive = true,
            ),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.nickname.ifBlank { "未命名账号" },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                RealmBadge(account.realm)
                if (account.enterprise) {
                    Spacer(Modifier.width(6.dp))
                    EnterpriseChip()
                }
                Spacer(Modifier.width(8.dp))
                StatusChip(text = acctStatusLabel(state), tone = acctStatusTone(state))
            }
            Spacer(Modifier.height(4.dp))
            Mono(account.uid, size = 12.sp, color = colors.ink3)

            Spacer(Modifier.height(16.dp))
            StatGrid(
                items = listOf(
                    StatTileData("余额", creditsText(account.credits), tone = Tone.Ok),
                    StatTileData(
                        label = "总额度",
                        value = if (account.creditsTotal < 0) "不限" else creditsText(account.creditsTotal),
                    ),
                    StatTileData(
                        label = "临期积分",
                        value = creditsText(account.creditsExpiring),
                        tone = if (account.creditsExpiring > 0) Tone.Warn else Tone.Neutral,
                    ),
                    StatTileData("成功次数", compactNum(account.successCount)),
                    StatTileData(
                        label = "连续失败",
                        value = compactNum(account.consecutiveFails.toLong()),
                        tone = if (account.consecutiveFails > 0) Tone.Bad else Tone.Neutral,
                    ),
                    StatTileData(
                        label = "在途",
                        value = compactNum(account.inFlight.toLong()),
                        tone = if (account.inFlight > 0) Tone.Accent else Tone.Neutral,
                    ),
                    StatTileData(
                        label = "熔断次数",
                        value = compactNum(account.breakerFails.toLong()),
                        tone = if (account.breakerFails > 0) Tone.Bad else Tone.Neutral,
                    ),
                ),
                columns = 2,
            )

            SectionHeader("状态")
            InfoRow("最近成功", agoText(account.lastSuccess))
            InfoRow("最近错误", account.lastErr.ifBlank { "—" })
            InfoRow("冷却原因", account.reason.ifBlank { "—" })
            InfoRow(
                label = "冷却剩余",
                value = if (account.cooling) remainText(account.coolRemainingSec) else "—",
            )
            InfoRow(
                label = "熔断剩余",
                value = acctBreakerRemaining(account).let { if (it > 0) remainText(it) else "—" },
            )
            InfoRow("临期最早到期", agoText(account.creditsEarliestExpiry))

            if (account.modelCosts.isNotEmpty()) {
                SectionHeader("模型成本")
                account.modelCosts.forEach { cost ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mono(
                            text = cost.model.ifBlank { "—" },
                            modifier = Modifier.weight(1f),
                            size = 12.sp,
                            color = colors.ink2,
                        )
                        Text(
                            text = "¥${acctCostText(cost.costPer1k)}/1k",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.ink,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${cost.samples} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.ink3,
                        )
                    }
                }
            }

            account.tokenUsage?.let { usage ->
                SectionHeader("Token 用量")
                InfoRow("请求数", compactNum(usage.requestCount), mono = true)
                InfoRow("输入 Tokens", compactNum(usage.promptTokens), mono = true)
                InfoRow("输出 Tokens", compactNum(usage.completionTokens), mono = true)
                InfoRow("总 Tokens", compactNum(usage.totalTokens), mono = true)
                InfoRow("最近延迟", msText(usage.lastLatencyMs), mono = true)
                InfoRow("最近速度", "${acctCostText(usage.lastTokensPerSecond)} tok/s", mono = true)
                InfoRow("最近模型", usage.lastModel.ifBlank { "—" }, mono = true)
                InfoRow("最近使用", agoText(usage.lastUsedAt))
            }

            SectionHeader("操作")
            Text(
                text = "提示：任务中心在底部「任务」页。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
            Spacer(Modifier.height(10.dp))

            running?.let { current ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在执行「${current.label}」…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink2,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            actions.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { item ->
                        WbButton(
                            text = item.label,
                            onClick = {
                                if (item.destructive) {
                                    confirmRemove = true
                                } else {
                                    run(item.action)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = running == null && item.enabled,
                            primary = item.primary,
                            tone = item.tone,
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "移除账号",
            text = "移除后不可恢复，确认移除该账号？",
            confirmText = "移除",
            destructive = true,
            onConfirm = {
                confirmRemove = false
                run(AccountAction.REMOVE)
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** 金额 / 速率展示：最多 4 位小数，去掉无意义的尾零。 */
private fun acctCostText(value: Double): String {
    val text = String.format(Locale.US, "%.4f", value)
    return text.trimEnd('0').trimEnd('.').ifBlank { "0" }
}

/** 账号动作结果 -> 提示文案。 */
internal fun acctActionResultMessage(result: ActionResult, action: AccountAction): String {
    if (!result.ok) {
        return result.message.ifBlank {
            result.balanceError.ifBlank {
                result.checkinMessage.ifBlank { result.error.ifBlank { "${action.label}失败" } }
            }
        }
    }
    if (action == AccountAction.CHECKIN && result.checkinMessage.isNotBlank()) {
        return result.checkinMessage
    }
    if (action == AccountAction.BALANCE && result.balanceError.isNotBlank()) {
        return "余额刷新失败：${result.balanceError}"
    }
    return result.message.ifBlank { "${action.label}成功" }
}

/** 批量动作结果 -> 提示文案；异步批量任务统一提示「已开始」。 */
internal fun acctBulkMessage(result: ActionResult, action: BulkAction): String {
    // 异步任务以 started 为成功标志，避免服务端只返回裸对象时误报失败。
    val succeeded = result.ok || (action.async && result.started)
    if (!succeeded) {
        return result.error.ifBlank {
            result.message.ifBlank {
                result.balanceError.ifBlank { "${action.label}失败" }
            }
        }
    }
    if (action.async) {
        return "已开始，稍后查看日志"
    }
    return result.message.ifBlank { "已刷新全部账号额度" }
}

/** 统一异常文案：HTTP 错误用服务端 message，网络错误给中文提示。 */
internal fun acctErrorMessage(error: Throwable): String = when (error) {
    is WbHttpException -> error.message
    is IOException -> error.message ?: "网络异常，请检查连接"
    else -> error.message ?: "操作失败"
}
