package com.workbuddy2api.panel.ui.accounts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy2api.panel.data.Account
import com.workbuddy2api.panel.data.BulkAction
import com.workbuddy2api.panel.data.Overview
import com.workbuddy2api.panel.data.WbHttpException
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.ConfirmDialog
import com.workbuddy2api.panel.ui.components.EmptyState
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.RealmBadge
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbProgress
import com.workbuddy2api.panel.ui.components.WbPullRefresh
import com.workbuddy2api.panel.ui.components.WbTextField
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberPollingLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.agoText
import com.workbuddy2api.panel.util.compactNum
import com.workbuddy2api.panel.util.creditsText
import com.workbuddy2api.panel.util.msText
import com.workbuddy2api.panel.util.remainText
import com.workbuddy2api.panel.util.shortUid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** 账号列表的状态筛选标签（顺序与 [AccountsScreen] 中的 index 一一对应）。 */
private val ACCOUNT_FILTERS = listOf("全部", "可用", "冷却", "暂停", "停用", "异常")

/** 顶部溢出菜单里的批量动作（顺序即展示顺序）。 */
private val BULK_ACTIONS = listOf(
    BulkAction.CHECKIN_ALL,
    BulkAction.TRAVEL_ALL,
    BulkAction.ACTIVITY_ALL,
    BulkAction.KEEPALIVE_ALL,
    BulkAction.BALANCE_ALL,
)

/**
 * 账号页：搜索 / 状态筛选 / 批量操作 / 添加账号。
 *
 * 数据通过 [WbRepository.overview] 5 秒轮询；点卡片打开 [AccountDetailSheet]。
 * 页面根是 Column（不建 Scaffold），顶部栏由本屏自带。
 */
@Composable
fun AccountsScreen(repo: WbRepository) {
    val colors = LocalWbColors.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var filterIndex by remember { mutableIntStateOf(0) }
    var selectedUid by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var pendingBulk by remember { mutableStateOf<BulkAction?>(null) }
    var bulkRunning by remember { mutableStateOf(false) }
    var balanceRunning by remember { mutableStateOf(false) }

    // 显式命名 load，避免 vararg 与尾随 lambda 的解析歧义。
    val loader = rememberPollingLoader(intervalMs = 5000L, load = { repo.overview() })
    // Ready 带泛型参数，显式写出 Overview 以免裸类型推断问题。
    val overview = (loader.state as? Loadable.Ready<Overview>)?.data
    val accounts = overview?.accounts ?: emptyList()
    val filtered = remember(accounts, query, filterIndex) { filterAccounts(accounts, query, filterIndex) }
    val subtitle = overview?.let { ov ->
        val total = if (ov.total > 0) ov.total else ov.accounts.size
        "共 $total 个 · 可用 ${ov.healthy}"
    }

    /** 顶栏刷新按钮：同步刷新全部账号额度后重载列表。 */
    fun refreshBalances() {
        if (balanceRunning) return
        balanceRunning = true
        scope.launch {
            try {
                val res = repo.bulkAction(BulkAction.BALANCE_ALL)
                snackbar.showSnackbar(acctBulkMessage(res, BulkAction.BALANCE_ALL))
            } catch (e: Exception) {
                snackbar.showSnackbar(acctErrorMessage(e))
            } finally {
                balanceRunning = false
                loader.refresh(quiet = true)
            }
        }
    }

    /** 执行批量动作；前 4 个是异步任务，balance_all 是同步返回。 */
    fun runBulk(action: BulkAction) {
        if (bulkRunning) return
        bulkRunning = true
        scope.launch {
            try {
                val res = repo.bulkAction(action)
                snackbar.showSnackbar(acctBulkMessage(res, action))
            } catch (e: Exception) {
                snackbar.showSnackbar(acctErrorMessage(e))
            } finally {
                bulkRunning = false
                loader.refresh(quiet = true)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        WbTopBar(title = "账号", subtitle = subtitle) {
            IconButton(onClick = { refreshBalances() }, enabled = !balanceRunning) {
                if (balanceRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新余额", tint = colors.ink)
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }, enabled = !bulkRunning) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "批量操作", tint = colors.ink)
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    BULK_ACTIONS.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label, style = MaterialTheme.typography.bodyMedium) },
                            enabled = !bulkRunning,
                            onClick = {
                                overflowOpen = false
                                pendingBulk = action
                            },
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            WbTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索",
                placeholder = "搜索昵称 / UID",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FilterChips(
                labels = ACCOUNT_FILTERS,
                selectedIndex = filterIndex,
                onSelect = { filterIndex = it },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = loader.state) {
                is Loadable.Loading -> LoadingBox(Modifier.fillMaxSize())
                is Loadable.Failed -> ErrorBox(s.message, onRetry = { loader.refresh() })
                // 其余情况即 Ready：用上面已取出的 overview 渲染，避免裸泛型类型判断。
                else -> WbPullRefresh(
                    refreshing = loader.refreshing,
                    onRefresh = { loader.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (filtered.isEmpty()) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                            item {
                                EmptyState(
                                    title = if (accounts.isEmpty()) "还没有账号" else "没有匹配的账号",
                                    subtitle = if (accounts.isEmpty()) {
                                        "点右下角「添加账号」开始授权"
                                    } else {
                                        "换个筛选条件或搜索词试试"
                                    },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(filtered, key = { it.uid }) { account ->
                                AccountCard(account = account, onClick = { selectedUid = account.uid })
                            }
                        }
                    }
                }
            }

            ExtendedFloatingActionButton(
                text = { Text("添加账号", style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { showAddSheet = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = colors.accent,
                contentColor = colors.bg,
            )
        }
    }

    // 详情弹层：从最新一轮轮询结果里取账号，保证弹层内数据也是实时的。
    val selected = selectedUid?.let { uid -> accounts.firstOrNull { it.uid == uid } }
    if (selected != null) {
        AccountDetailSheet(
            account = selected,
            repo = repo,
            onDismiss = { selectedUid = null },
            onChanged = { loader.refresh(quiet = true) },
        )
    }

    if (showAddSheet) {
        AddAccountSheet(
            repo = repo,
            onDismiss = { showAddSheet = false },
            onChanged = { loader.refresh(quiet = true) },
        )
    }

    val bulk = pendingBulk
    if (bulk != null) {
        ConfirmDialog(
            title = bulk.label,
            text = if (bulk.async) {
                "将对所有账号执行「${bulk.label}」，任务在后台运行，稍后可在日志页查看。"
            } else {
                "将对所有账号执行「${bulk.label}」，确认继续？"
            },
            confirmText = "执行",
            onConfirm = {
                pendingBulk = null
                runBulk(bulk)
            },
            onDismiss = { pendingBulk = null },
        )
    }
}

/** 单张账号卡片：昵称/版本/状态 + UID/成功数 + 额度进度 + 冷却信息 + 用量摘要。 */
@Composable
private fun AccountCard(account: Account, onClick: () -> Unit) {
    val colors = LocalWbColors.current
    val state = acctState(account)

    WbCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = account.nickname.ifBlank { "未命名账号" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            RealmBadge(account.realm)
            if (account.enterprise) {
                Spacer(Modifier.width(6.dp))
                EnterpriseChip()
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(text = acctStatusLabel(state), tone = acctStatusTone(state))
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(shortUid(account.uid), size = 12.sp, color = colors.ink3)
            AcctDot()
            Text(
                text = "${account.successCount} 次成功",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink2,
            )
            AcctDot()
            Text(
                text = "最近成功 ${agoText(account.lastSuccess)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "额度",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = creditsText(account.credits),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = colors.ink,
            )
            Text(
                text = " / ${if (account.creditsTotal < 0) "不限" else creditsText(account.creditsTotal)}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = colors.ink3,
            )
        }
        Spacer(Modifier.height(6.dp))
        WbProgress(value = acctCreditsRatio(account), tone = acctCreditsTone(account))
        if (account.creditsExpiring > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "临期 ${creditsText(account.creditsExpiring)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warn,
            )
        }

        val stateLine: Pair<String, Color>? = when (state) {
            AcctState.Cooling -> "冷却中 · 剩余 ${remainText(account.coolRemainingSec)} · ${acctCoolKindLabel(account.coolKind)}" to colors.warn
            AcctState.Paused -> "已暂停选号" to colors.ink3
            AcctState.Disabled -> "已禁用" to colors.bad
            AcctState.Breaker -> "熔断中 · 剩余 ${remainText(acctBreakerRemaining(account))}" to colors.bad
            AcctState.Available -> null
        }
        if (stateLine != null) {
            Spacer(Modifier.height(6.dp))
            Text(stateLine.first, style = MaterialTheme.typography.bodySmall, color = stateLine.second)
        }

        account.tokenUsage?.let { usage ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请求 ${compactNum(usage.requestCount)} · Tokens ${compactNum(usage.totalTokens)} · " +
                    "延迟 ${msText(usage.lastLatencyMs)} · ${usage.lastModel.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 卡片里的分隔圆点。 */
@Composable
private fun AcctDot() {
    Text(
        text = "·",
        style = MaterialTheme.typography.bodySmall,
        color = LocalWbColors.current.ink3,
        modifier = Modifier.padding(horizontal = 5.dp),
    )
}

/** 「企业」小标签（企业版账号额度不限）。 */
@Composable
internal fun EnterpriseChip(modifier: Modifier = Modifier) {
    val colors = LocalWbColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.accentSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("企业", style = MaterialTheme.typography.labelSmall, color = colors.accent)
    }
}

/** 账号展示状态（按服务端语义归并）。 */
internal enum class AcctState { Available, Cooling, Paused, Disabled, Breaker }

/** 熔断剩余秒数（breaker_until 为空或已过期时为 0）。 */
internal fun acctBreakerRemaining(account: Account): Long {
    if (account.breakerUntil.isBlank()) return 0
    val until = com.workbuddy2api.panel.util.parseIso(account.breakerUntil) ?: return 0
    return Duration.between(Instant.now(), until).seconds.coerceAtLeast(0)
}

/** 账号当前状态：禁用 > 冷却 > 暂停 > 熔断 > 可用。 */
internal fun acctState(account: Account): AcctState = when {
    account.disabled -> AcctState.Disabled
    account.cooling -> AcctState.Cooling
    account.paused -> AcctState.Paused
    acctBreakerRemaining(account) > 0 -> AcctState.Breaker
    else -> AcctState.Available
}

/** 状态标签文案。 */
internal fun acctStatusLabel(state: AcctState): String = when (state) {
    AcctState.Available -> "可用"
    AcctState.Cooling -> "冷却"
    AcctState.Paused -> "暂停"
    AcctState.Disabled -> "停用"
    AcctState.Breaker -> "熔断"
}

/** 状态语义色：健康绿、冷却黄、禁用/熔断红、暂停灰。 */
internal fun acctStatusTone(state: AcctState): Tone = when (state) {
    AcctState.Available -> Tone.Ok
    AcctState.Cooling -> Tone.Warn
    AcctState.Paused -> Tone.Neutral
    AcctState.Disabled -> Tone.Bad
    AcctState.Breaker -> Tone.Bad
}

/** 冷却类型 -> 中文说明；未知类型原样展示。 */
internal fun acctCoolKindLabel(kind: String): String = when (kind.trim().lowercase()) {
    "", "unknown", "none" -> "未知原因"
    "rate_limit", "ratelimit", "rate", "429" -> "限流"
    "quota", "credits", "credit", "insufficient_credits" -> "额度不足"
    "auth", "unauthorized", "401" -> "鉴权失败"
    "error", "failure", "fail", "consecutive_fails" -> "连续失败"
    "model", "model_lock", "model_locked" -> "模型锁定"
    "network", "timeout" -> "网络异常"
    else -> kind
}

/** 额度使用比例（0..1）；企业版不限额度视为满格。 */
internal fun acctCreditsRatio(account: Account): Float {
    if (account.creditsTotal < 0) return 1f
    if (account.creditsTotal <= 0.0) return 0f
    return (account.credits / account.creditsTotal).toFloat().coerceIn(0f, 1f)
}

/** 额度进度条语义色：≥50% 绿、≥20% 黄、其余红。 */
internal fun acctCreditsTone(account: Account): Tone {
    if (account.creditsTotal < 0) return Tone.Ok
    val ratio = acctCreditsRatio(account)
    return when {
        ratio >= 0.5f -> Tone.Ok
        ratio >= 0.2f -> Tone.Warn
        else -> Tone.Bad
    }
}

/** 按搜索词 + 状态筛选账号。 */
private fun filterAccounts(accounts: List<Account>, query: String, filterIndex: Int): List<Account> {
    val q = query.trim().lowercase()
    return accounts.filter { account ->
        val matchQuery = q.isEmpty() ||
            account.nickname.lowercase().contains(q) ||
            account.uid.lowercase().contains(q)
        if (!matchQuery) return@filter false
        when (filterIndex) {
            1 -> acctState(account) == AcctState.Available
            2 -> acctState(account) == AcctState.Cooling
            3 -> acctState(account) == AcctState.Paused
            4 -> acctState(account) == AcctState.Disabled
            5 -> acctState(account) == AcctState.Breaker || account.consecutiveFails > 0
            else -> true
        }
    }
}

/**
 * 添加账号弹层：选择版本 -> 调 [WbRepository.loginStart] 拿授权链接 ->
 * 用系统浏览器打开 -> 每 3 秒 [WbRepository.loginPoll] 轮询，成功后自动关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountSheet(
    repo: WbRepository,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val colors = LocalWbColors.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var realm by remember { mutableStateOf("cn") }
    var starting by remember { mutableStateOf(false) }
    var loginState by remember { mutableStateOf<String?>(null) }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var doneNickname by remember { mutableStateOf<String?>(null) }
    var doneCredits by remember { mutableStateOf<Double?>(null) }

    /** 用系统浏览器打开授权页；没有浏览器时降级为提示。 */
    fun openInBrowser(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("未找到浏览器，请手动打开授权链接") }
        } catch (e: Exception) {
            scope.launch { snackbar.showSnackbar("无法打开授权链接") }
        }
    }

    /** 创建授权会话并打开浏览器。 */
    fun startLogin() {
        if (starting) return
        starting = true
        error = null
        scope.launch {
            try {
                val start = repo.loginStart(realm)
                // 服务端可能用 200 + error 返回失败，或缺少 url/state，这里统一兜底。
                if (start.url.isBlank() || start.state.isBlank()) {
                    error = start.error.ifBlank { "服务端未返回授权链接" }
                    return@launch
                }
                authUrl = start.url
                loginState = start.state
                openInBrowser(start.url)
            } catch (e: Exception) {
                error = acctErrorMessage(e)
            } finally {
                starting = false
            }
        }
    }

    // 轮询授权结果：key 为 state，弹层关闭（composable 离开）时自动取消。
    LaunchedEffect(loginState) {
        val state = loginState ?: return@LaunchedEffect
        while (true) {
            delay(3000L)
            try {
                val poll = repo.loginPoll(state)
                if (poll.done) {
                    doneNickname = poll.nickname
                    doneCredits = poll.credits
                    done = true
                    onChanged()
                    delay(1500L)
                    onDismiss()
                    break
                }
                // 服务端在 state 过期时也可能用 200 + error 返回，这里一并终止轮询。
                if (poll.error.isNotBlank()) {
                    error = poll.error
                    loginState = null
                    break
                }
            } catch (e: WbHttpException) {
                error = if (e.status == 404) "授权已过期，请重新开始" else e.message
                loginState = null
                break
            } catch (e: Exception) {
                error = acctErrorMessage(e)
                loginState = null
                break
            }
        }
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("添加账号", style = MaterialTheme.typography.titleLarge, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "选择版本后在系统浏览器完成授权，App 会自动获取结果。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )

            Spacer(Modifier.height(16.dp))
            Text("版本", style = MaterialTheme.typography.labelLarge, color = colors.ink2)
            Spacer(Modifier.height(8.dp))
            FilterChips(
                labels = listOf("国内版", "国际版"),
                selectedIndex = if (realm == "global") 1 else 0,
                onSelect = { index -> realm = if (index == 1) "global" else "cn" },
            )

            Spacer(Modifier.height(20.dp))
            when {
                done -> {
                    Text("授权成功", style = MaterialTheme.typography.titleMedium, color = colors.ok)
                    Spacer(Modifier.height(4.dp))
                    val creditsSuffix = doneCredits
                        ?.let { if (it >= 0) " · 余额 ${creditsText(it)}" else "" }
                        .orEmpty()
                    Text(
                        text = "已添加 ${doneNickname ?: "新账号"}$creditsSuffix",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink2,
                    )
                }

                loginState != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "等待浏览器完成授权…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink2,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "浏览器未自动打开？可点下方按钮重新打开授权页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.height(12.dp))
                    WbButton(
                        text = "重新打开授权页",
                        onClick = { authUrl?.let { openInBrowser(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    WbButton(
                        text = when {
                            starting -> "正在创建授权…"
                            error != null -> "重新授权"
                            else -> "开始授权"
                        },
                        onClick = { startLogin() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !starting,
                        primary = true,
                    )
                }
            }

            error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.bad)
            }
        }
    }
}
