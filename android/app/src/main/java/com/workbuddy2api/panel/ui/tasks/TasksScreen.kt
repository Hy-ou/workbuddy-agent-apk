package com.workbuddy2api.panel.ui.tasks

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy2api.panel.data.Account
import com.workbuddy2api.panel.data.AccountTasks
import com.workbuddy2api.panel.data.ActionResult
import com.workbuddy2api.panel.data.Overview
import com.workbuddy2api.panel.data.QueueStartResult
import com.workbuddy2api.panel.data.QueueStatus
import com.workbuddy2api.panel.data.ScanResult
import com.workbuddy2api.panel.data.TaskItem
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
import com.workbuddy2api.panel.ui.components.SectionHeader
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbProgress
import com.workbuddy2api.panel.ui.components.WbPullRefresh
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.state.rememberPollingLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.util.creditsText
import com.workbuddy2api.panel.util.shortUid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 队列并发选项（服务端支持 1–4，面板只暴露手机端常用的 1–3 档）。 */
private val CONCURRENCY_OPTIONS = listOf("并发 1", "并发 2", "并发 3")

/** 队列运行时的轮询间隔（规范：队列 3s，且仅 running 时轮询）。 */
private const val QUEUE_POLL_MS = 3_000L

/** 账号列表的轮询间隔（规范：总览/账号 5s）。 */
private const val OVERVIEW_POLL_MS = 5_000L

/** 需要二次确认的高风险动作。 */
private sealed interface PendingConfirm {
    /** 启动执行队列：会把扫描到的全部待办排队执行。 */
    data class RunQueue(val concurrency: Int) : PendingConfirm

    /** 单账号一键完成全部任务（服务端最长 5 分钟）。 */
    data class AutoAll(val uid: String, val nickname: String) : PendingConfirm
}

/**
 * 任务中心：队列状态 / 扫描待办 / 执行队列 + 按账号展开的任务列表。
 *
 * 数据来源：
 * - [WbRepository.overview] 5 秒轮询，只取账号清单（昵称 / UID / 区服）；
 * - [WbRepository.taskQueue] 首屏拉一次，仅在 `running` 时每 3 秒轮询，结束自动停；
 * - [WbRepository.accountTasks] 在账号卡片展开时按需拉取，动作完成后重新拉取。
 *
 * 所有动作结果统一走 snackbar；长任务（accept_all / auto_all / auto）用按钮禁用 +
 * 「执行中，请稍候…」行内提示，避免重复点击（服务端对同账号动作是互斥的）。
 * 屏幕不自建 Scaffold，顶部栏由本屏自带（规范第 6 节）。
 */
@Composable
fun TasksScreen(repo: WbRepository) {
    val colors = LocalWbColors.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    // 账号清单（5s 轮询）；队列状态首屏拉一次，running 时另行 3s 轮询。
    val overviewLoader = rememberPollingLoader(OVERVIEW_POLL_MS, true, repo, load = { repo.overview() })
    val queueLoader = rememberLoader(repo, load = { repo.taskQueue() })

    val overview = (overviewLoader.state as? Loadable.Ready<Overview>)?.data
    val accounts = overview?.accounts.orEmpty()
    val queue = (queueLoader.state as? Loadable.Ready<QueueStatus>)?.data
    val queueError = (queueLoader.state as? Loadable.Failed)?.message
    val queueRunning = queue?.running == true

    var concurrency by remember { mutableIntStateOf(2) }
    var scanning by remember { mutableStateOf(false) }
    var queueStarting by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }

    // 账号级长动作（全部接受 / 一键完成全部）：记录 uid 与动作名，用于行内进度提示。
    var busyAccount by remember { mutableStateOf<String?>(null) }
    var busyAccountLabel by remember { mutableStateOf("") }
    // 单任务动作：key = "uid|task_code"，只让该行转圈。
    var busyTaskKey by remember { mutableStateOf<String?>(null) }

    // 当前展开的账号与其任务列表状态。
    var expandedUid by remember { mutableStateOf<String?>(null) }
    var tasksUid by remember { mutableStateOf("") }
    var tasksState by remember { mutableStateOf<Loadable<AccountTasks>>(Loadable.Loading) }
    var tasksReload by remember { mutableIntStateOf(0) }

    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }

    val anyBusy = busyAccount != null || busyTaskKey != null

    // 队列运行中每 3s 静默刷新；running 变 false 时 effect 重启并直接返回，轮询自动停止。
    LaunchedEffect(queueRunning) {
        if (!queueRunning) return@LaunchedEffect
        while (isActive) {
            delay(QUEUE_POLL_MS)
            queueLoader.refresh(quiet = true)
        }
    }

    // 展开账号后拉取任务列表；tasksReload 递增表示动作完成后重新拉取（保留旧内容避免闪烁）。
    LaunchedEffect(expandedUid, tasksReload) {
        val uid = expandedUid ?: return@LaunchedEffect
        if (tasksUid != uid) {
            tasksUid = uid
            tasksState = Loadable.Loading
        }
        try {
            tasksState = Loadable.Ready(repo.accountTasks(uid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val previous = tasksState
            if (previous is Loadable.Ready<*>) {
                // 已有数据时保留旧列表，只提示失败。
                snackbar.showSnackbar(errText(e))
            } else {
                tasksState = Loadable.Failed(errText(e))
            }
        }
    }

    /** 顶栏 / 下拉刷新：账号、队列、已展开账号的任务一起刷新。 */
    fun refreshAll() {
        overviewLoader.refresh(quiet = true)
        queueLoader.refresh(quiet = true)
        if (expandedUid != null) tasksReload++
    }

    /** 扫描全部账号的待办任务（只读，通常几秒）。 */
    fun scanTasks() {
        if (scanning || queueStarting) return
        scanning = true
        scope.launch {
            snackbar.runAction {
                val res = repo.scanAll()
                scanResult = res
                val pending = if (res.pendingCount > 0) {
                    res.pendingCount
                } else {
                    res.accounts.orEmpty().sumOf { it.growth?.size ?: 0 }
                }
                if (pending > 0) "扫描完成：待办 $pending 项" else "扫描完成：暂无待办"
            }
            scanning = false
        }
    }

    /** 启动执行队列；返回后立刻拉一次队列状态，让卡片切到 running 视图。 */
    fun runQueue(conc: Int) {
        if (queueStarting || scanning) return
        queueStarting = true
        scope.launch {
            snackbar.runAction { queueStartMessage(repo.runQueue(conc)) }
            queueStarting = false
            queueLoader.refresh(quiet = true)
        }
    }

    /** 接受该账号全部未接受任务（服务端分批 + 节流，可能十几秒）。 */
    fun acceptAll(uid: String) {
        if (anyBusy) return
        busyAccount = uid
        busyAccountLabel = "全部接受"
        scope.launch {
            snackbar.runAction { acceptAllMessage(repo.acceptAllTasks(uid)) }
            busyAccount = null
            busyAccountLabel = ""
            if (expandedUid == uid) tasksReload++
        }
    }

    /** 一键完成该账号全部可自动化任务（服务端最长 5 分钟，超时 504 但仍在后台执行）。 */
    fun autoAll(uid: String) {
        if (anyBusy) return
        busyAccount = uid
        busyAccountLabel = "一键完成全部"
        scope.launch {
            snackbar.runAction { autoAllMessage(repo.autoAllTasks(uid)) }
            busyAccount = null
            busyAccountLabel = ""
            if (expandedUid == uid) tasksReload++
        }
    }

    /** 领取单任务奖励（进度达标才可领）。 */
    fun claimTask(uid: String, task: TaskItem) {
        if (anyBusy) return
        busyTaskKey = taskKey(uid, task.taskCode)
        scope.launch {
            snackbar.runAction { claimMessage(repo.claimTask(uid, task.taskCode)) }
            busyTaskKey = null
            if (expandedUid == uid) tasksReload++
        }
    }

    /** 自动完成单任务（真实跑一次行为事件，可能十几秒）。 */
    fun autoTask(uid: String, task: TaskItem) {
        if (anyBusy) return
        busyTaskKey = taskKey(uid, task.taskCode)
        scope.launch {
            snackbar.runAction { autoTaskMessage(repo.autoTask(uid, task.taskCode)) }
            busyTaskKey = null
            if (expandedUid == uid) tasksReload++
        }
    }

    Column(Modifier.fillMaxSize()) {
        WbTopBar(title = "任务中心", subtitle = "自动任务与队列进度") {
            IconButton(onClick = { refreshAll() }, enabled = !overviewLoader.refreshing) {
                if (overviewLoader.refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = colors.ink)
                }
            }
        }

        when (val s = overviewLoader.state) {
            is Loadable.Loading -> LoadingBox(Modifier.fillMaxSize())
            is Loadable.Failed -> ErrorBox(s.message, onRetry = { refreshAll() })
            else -> WbPullRefresh(
                refreshing = overviewLoader.refreshing,
                onRefresh = { refreshAll() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "queue") {
                        QueueCard(
                            queue = queue,
                            queueError = queueError,
                            scanning = scanning,
                            starting = queueStarting,
                            concurrency = concurrency,
                            onConcurrencyChange = { concurrency = it },
                            onScan = { scanTasks() },
                            onRun = { pendingConfirm = PendingConfirm.RunQueue(concurrency) },
                            onRetryQueue = { queueLoader.refresh() },
                        )
                    }

                    val scanSnapshot = scanResult
                    if (scanSnapshot != null) {
                        item(key = "scan") { ScanResultCard(scanSnapshot) }
                    }

                    item(key = "accounts-header") {
                        SectionHeader(
                            title = "账号任务",
                            subtitle = "共 ${accounts.size} 个账号",
                        )
                    }

                    if (accounts.isEmpty()) {
                        item(key = "accounts-empty") {
                            EmptyState(
                                title = "还没有账号",
                                subtitle = "先在账号页授权账号，才能查看与自动化任务",
                            )
                        }
                    }

                    items(accounts, key = { it.uid }) { account ->
                        val uid = account.uid
                        val expanded = expandedUid == uid
                        AccountTasksCard(
                            account = account,
                            expanded = expanded,
                            tasksState = if (tasksUid == uid) tasksState else null,
                            busyLabel = if (busyAccount == uid) busyAccountLabel else null,
                            busyTaskKey = busyTaskKey,
                            anyBusy = anyBusy,
                            onToggle = {
                                if (expanded) {
                                    expandedUid = null
                                } else {
                                    expandedUid = uid
                                    tasksReload = 0
                                }
                            },
                            onAcceptAll = { acceptAll(uid) },
                            onAutoAll = {
                                pendingConfirm = PendingConfirm.AutoAll(uid, account.nickname)
                            },
                            onRetry = { tasksReload++ },
                            onClaim = { claimTask(uid, it) },
                            onAutoTask = { autoTask(uid, it) },
                        )
                    }

                    item(key = "tail") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    when (val pc = pendingConfirm) {
        is PendingConfirm.RunQueue -> ConfirmDialog(
            title = "执行全部待办",
            text = "将扫描全部账号，并按并发 ${pc.concurrency} 执行待办任务。过程可能持续数分钟，" +
                "可在队列卡片查看实时进度。",
            confirmText = "开始执行",
            onConfirm = {
                pendingConfirm = null
                runQueue(pc.concurrency)
            },
            onDismiss = { pendingConfirm = null },
        )

        is PendingConfirm.AutoAll -> ConfirmDialog(
            title = "一键完成全部",
            text = "将对「${pc.nickname.ifBlank { shortUid(pc.uid) }}」执行全部可自动化任务，" +
                "可能耗时数分钟。执行期间请勿重复点击，也不要离开本页太远。",
            confirmText = "开始执行",
            onConfirm = {
                pendingConfirm = null
                autoAll(pc.uid)
            },
            onDismiss = { pendingConfirm = null },
        )

        null -> Unit
    }
}

// ---------------------------------------------------------------------------
// 队列卡片
// ---------------------------------------------------------------------------

/**
 * 队列状态卡片：运行中显示完成数 / 进度条 / 当前执行项；空闲时显示上一轮结果。
 * 底部是「扫描待办」「执行全部待办」两个主操作与并发档位选择。
 */
@Composable
private fun QueueCard(
    queue: QueueStatus?,
    queueError: String?,
    scanning: Boolean,
    starting: Boolean,
    concurrency: Int,
    onConcurrencyChange: (Int) -> Unit,
    onScan: () -> Unit,
    onRun: () -> Unit,
    onRetryQueue: () -> Unit,
) {
    val c = LocalWbColors.current
    val running = queue?.running == true
    val items = queue?.items.orEmpty()
    val total = queue?.total ?: items.size
    val done = items.count { it.status == "done" }
    val failed = items.count { it.status == "error" }
    val current = items.firstOrNull { it.status == "running" }

    WbCard {
        SectionHeader(
            title = "队列状态",
            subtitle = when {
                running -> "并发 ${queue?.conc?.takeIf { it > 0 } ?: concurrency} · 执行中"
                total > 0 -> "上一轮共 $total 项"
                else -> "尚未执行"
            },
            trailing = {
                StatusChip(
                    text = if (running) "运行中" else "空闲",
                    tone = if (running) Tone.Accent else Tone.Neutral,
                )
            },
        )

        if (queue == null) {
            if (queueError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = queueError,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.bad,
                )
                Spacer(Modifier.height(8.dp))
                WbButton(text = "重试", onClick = onRetryQueue)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "队列状态加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink3,
                )
            }
        } else if (running) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink3,
                )
                Spacer(Modifier.width(8.dp))
                Mono(text = "$done / $total", size = 15.sp, color = c.ink)
                Spacer(Modifier.weight(1f))
                if (failed > 0) StatusChip(text = "失败 $failed", tone = Tone.Bad)
            }
            Spacer(Modifier.height(8.dp))
            WbProgress(
                value = if (total > 0) done.toFloat() / total.toFloat() else 0f,
                tone = Tone.Accent,
            )
            if (current != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${current.nickname.ifBlank { shortUid(current.uid) }} · ${current.code}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current.message.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ink3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (total > 0) {
                    "已完成 $done / $total 项"
                } else {
                    "队列空闲，先扫描待办，再执行全部待办"
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.ink3,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WbButton(
                text = if (scanning) "扫描中…" else "扫描待办",
                onClick = onScan,
                enabled = !scanning && !starting,
                modifier = Modifier.weight(1f),
            )
            WbButton(
                text = if (starting) "启动中…" else "执行全部待办",
                onClick = onRun,
                enabled = !starting && !scanning && !running,
                primary = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "并发数",
            style = MaterialTheme.typography.labelMedium,
            color = c.ink3,
        )
        Spacer(Modifier.height(2.dp))
        FilterChips(
            labels = CONCURRENCY_OPTIONS,
            selectedIndex = (concurrency - 1).coerceIn(0, CONCURRENCY_OPTIONS.lastIndex),
            onSelect = { onConcurrencyChange(it + 1) },
        )
    }
}

// ---------------------------------------------------------------------------
// 扫描结果卡片
// ---------------------------------------------------------------------------

/**
 * 扫描结果：按账号分组列出待办任务（账号内按服务端下发顺序）。
 *
 * 空 uid 的占位项一律过滤（服务端为每个账号预留数组槽位，禁用账号会留下空项）；
 * 没有任何待办时展示「暂无待办」空状态。
 */
@Composable
private fun ScanResultCard(scan: ScanResult) {
    val c = LocalWbColors.current
    val groups = scan.accounts.orEmpty().filter { it.uid.isNotBlank() }
    val pending = if (scan.pendingCount > 0) {
        scan.pendingCount
    } else {
        groups.sumOf { it.growth?.size ?: 0 }
    }
    val shown = groups.filter { !it.growth.isNullOrEmpty() || it.growthError.isNotBlank() }

    WbCard {
        SectionHeader(
            title = "扫描结果",
            subtitle = if (pending > 0) "待办 $pending 项" else null,
        )

        if (shown.isEmpty() || pending == 0) {
            EmptyState(
                title = "暂无待办",
                subtitle = "所有可自动化任务都已完成或已领取",
            )
        } else {
            shown.forEachIndexed { index, group ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    DividerLine()
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.nickname.ifBlank { "未命名账号" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Mono(text = shortUid(group.uid), size = 11.5.sp, color = c.ink3)
                }
                if (group.growthError.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = group.growthError,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.bad,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                group.growth.orEmpty().forEach { task ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(toneColor(scanTone(task))),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = task.title.ifBlank { task.taskCode },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Mono(text = progressText(task), size = 11.5.sp, color = c.ink3)
                                val reward = rewardText(task)
                                if (reward.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = reward,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.ink3,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (task.title.isNotBlank() && task.taskCode.isNotBlank()) {
                                Spacer(Modifier.height(1.dp))
                                Mono(text = task.taskCode, size = 10.5.sp, color = c.ink3)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 账号任务卡片
// ---------------------------------------------------------------------------

/**
 * 单个账号的可展开任务卡片：收起时只显示昵称 / UID / 区服，展开后加载任务列表，
 * 并提供「全部接受」「一键完成全部」两个长动作（执行中显示行内进度并禁用按钮）。
 */
@Composable
private fun AccountTasksCard(
    account: Account,
    expanded: Boolean,
    tasksState: Loadable<AccountTasks>?,
    busyLabel: String?,
    busyTaskKey: String?,
    anyBusy: Boolean,
    onToggle: () -> Unit,
    onAcceptAll: () -> Unit,
    onAutoAll: () -> Unit,
    onRetry: () -> Unit,
    onClaim: (TaskItem) -> Unit,
    onAutoTask: (TaskItem) -> Unit,
) {
    val c = LocalWbColors.current
    val tasks = (tasksState as? Loadable.Ready<AccountTasks>)?.data?.tasks.orEmpty()
    val summary = if (tasks.isEmpty()) {
        null
    } else {
        "任务 ${tasks.size} · 已领取 ${tasks.count { it.claimed }}"
    }

    WbCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.nickname.ifBlank { "未命名账号" },
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(text = shortUid(account.uid), size = 11.5.sp, color = c.ink3)
                    if (summary != null) {
                        Text(
                            text = " · $summary",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.ink3,
                            maxLines = 1,
                        )
                    }
                }
            }
            RealmBadge(account.realm)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = c.ink3,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WbButton(
                    text = "全部接受",
                    onClick = onAcceptAll,
                    enabled = !anyBusy,
                    modifier = Modifier.weight(1f),
                )
                WbButton(
                    text = "一键完成全部",
                    onClick = onAutoAll,
                    enabled = !anyBusy,
                    modifier = Modifier.weight(1f),
                )
            }

            if (busyLabel != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = c.accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "执行中，请稍候…（$busyLabel 可能需要数分钟）",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.accent,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            when (tasksState) {
                null -> LoadingBox()
                is Loadable.Loading -> LoadingBox()
                is Loadable.Failed -> ErrorBox(tasksState.message, onRetry = onRetry)
                else -> {
                    if (tasks.isEmpty()) {
                        EmptyState(
                            title = "暂无任务",
                            subtitle = "该账号没有可展示的成长任务（国际版 / 企业版无个人成长体系）",
                        )
                    } else {
                        tasks.forEachIndexed { index, task ->
                            if (index > 0) {
                                Spacer(Modifier.height(8.dp))
                                DividerLine()
                                Spacer(Modifier.height(8.dp))
                            }
                            TaskRow(
                                task = task,
                                busy = busyTaskKey == taskKey(account.uid, task.taskCode),
                                anyBusy = anyBusy,
                                onClaim = { onClaim(task) },
                                onAuto = { onAutoTask(task) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单行任务：标题 + 接受状态徽标 / 进度 + 奖励 + 行内动作按钮 / 进度条。 */
@Composable
private fun TaskRow(
    task: TaskItem,
    busy: Boolean,
    anyBusy: Boolean,
    onClaim: () -> Unit,
    onAuto: () -> Unit,
) {
    val c = LocalWbColors.current
    val badge = acceptBadge(task)
    val reward = rewardText(task)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.title.ifBlank { task.taskCode },
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusChip(text = badge.first, tone = badge.second)
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(text = progressText(task), size = 12.sp, color = c.ink2)
            if (reward.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = reward,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.ink3,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                task.claimed -> Unit
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = c.accent,
                )
                task.locked -> StatusChip(text = "未解锁", tone = Tone.Neutral)
                task.claimable -> WbButton(
                    text = "领取",
                    onClick = onClaim,
                    enabled = !anyBusy,
                    primary = true,
                )
                else -> WbButton(
                    text = "一键完成",
                    onClick = onAuto,
                    enabled = !anyBusy,
                )
            }
        }

        if (task.target > 0) {
            Spacer(Modifier.height(6.dp))
            WbProgress(
                value = task.current.toFloat() / task.target.toFloat(),
                tone = if (task.current >= task.target) Tone.Ok else Tone.Accent,
            )
        }
    }
}

/** 卡片内分隔线：1dp 的 lineSoft 细线，避免整块卡片分隔导致视觉过重。 */
@Composable
private fun DividerLine() {
    val c = LocalWbColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.lineSoft),
    )
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

/** 语义色 → 实际颜色（口径与 Common.kt 内部同名函数一致）。 */
@Composable
private fun toneColor(tone: Tone): Color {
    val c = LocalWbColors.current
    return when (tone) {
        Tone.Neutral -> c.ink3
        Tone.Ok -> c.ok
        Tone.Warn -> c.warn
        Tone.Bad -> c.bad
        Tone.Accent -> c.accent
    }
}

/** 进度文案：`3/5`；无目标时退化为当前值。 */
private fun progressText(task: TaskItem): String = when {
    task.target > 0 -> "${task.current}/${task.target}"
    else -> "${task.current}"
}

/** 奖励文案：`300 分 · 5 能`，两者都为 0 时返回空串（不占位）。 */
private fun rewardText(task: TaskItem): String {
    val parts = buildList {
        if (task.credit > 0) add("${creditsText(task.credit)} 分")
        if (task.energy > 0) add("${creditsText(task.energy)} 能")
    }
    return parts.joinToString(" · ")
}

/** 接受状态徽标：未接受（灰）/ 已接受（强调）/ 已完成（黄）/ 已领取（绿）。 */
private fun acceptBadge(task: TaskItem): Pair<String, Tone> = when {
    task.claimed || task.acceptStatus == "claimed" -> "已领取" to Tone.Ok
    task.acceptStatus == "completed" -> "已完成" to Tone.Warn
    task.acceptStatus == "accepted" -> "已接受" to Tone.Accent
    task.acceptStatus.isBlank() || task.acceptStatus == "not_accepted" -> "未接受" to Tone.Neutral
    else -> task.acceptStatus to Tone.Neutral
}

/** 扫描结果的状态点：已领取灰 / 可领取或达标绿 / 其余（待自动化）强调色。 */
private fun scanTone(task: TaskItem): Tone = when {
    task.claimed -> Tone.Neutral
    task.claimable || (task.target > 0 && task.current >= task.target) -> Tone.Ok
    else -> Tone.Accent
}

/** 任务动作的唯一 key（账号 + 任务码），用于行内转圈定位。 */
private fun taskKey(uid: String, code: String): String = "$uid|$code"

/** 把异常转成可直接展示的中文文案。 */
private fun errText(e: Throwable): String = when (e) {
    is WbHttpException -> e.message
    else -> e.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
}

/**
 * 统一动作执行壳：跑 [block] 并把结果（或异常文案）弹到 snackbar。
 *
 * 取消异常必须继续向上抛，否则离开页面时协程会被吞掉；504 单独识别为
 * 「仍在后台执行」——auto_all 服务端超时返回 504 时流水线其实还在跑。
 */
private suspend fun SnackbarHostState.runAction(block: suspend () -> String) {
    try {
        showSnackbar(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: WbHttpException) {
        val text = when (e.status) {
            504 -> "执行超时，任务仍在后台执行，稍后刷新查看结果"
            409 -> "该账号已有任务动作在执行中，请稍后再试"
            else -> e.message
        }
        showSnackbar(text)
    } catch (e: Exception) {
        showSnackbar(errText(e))
    }
}

// ---------------------------------------------------------------------------
// 响应文案
// ---------------------------------------------------------------------------

/** `accept_all` 返回 `{ok, accepted, failed[]}`；全部已接受时给明确提示。 */
private fun acceptAllMessage(res: JsonObject): String {
    val accepted = res.intOrZero("accepted")
    val failed = res.arrayOrNull("failed")?.size ?: 0
    val hint = res.stringOrEmpty("message")
    return when {
        accepted == 0 && failed == 0 -> hint.ifBlank { "所有任务均已接受" }
        failed > 0 -> "已接受 $accepted 个 · 失败 $failed 个，可稍后重试"
        else -> "已接受 $accepted 个任务"
    }
}

/** `auto_all` 返回 `{ok, results:[{task_code,status,message}]}`；按状态汇总条数。 */
private fun autoAllMessage(res: JsonObject): String {
    val results = res.arrayOrNull("results")?.mapNotNull { it as? JsonObject }.orEmpty()
    if (results.isEmpty()) return "没有可自动化的任务"
    val done = results.count { it.stringOrEmpty("status") == "done" }
    val skipped = results.count { it.stringOrEmpty("status") == "skipped" }
    val failed = results.count { it.stringOrEmpty("status") == "error" }
    return buildString {
        append("一键完成结束：完成 $done")
        if (skipped > 0) append(" · 跳过 $skipped")
        if (failed > 0) append(" · 失败 $failed")
    }
}

/** `run_queue` 返回 `{ok, started, total, message}`；未启动时优先用服务端说明。 */
private fun queueStartMessage(res: QueueStartResult): String = when {
    res.started -> if (res.total > 0) "已开始执行，共 ${res.total} 项待办" else "已开始执行"
    res.message.isNotBlank() -> res.message
    res.error.isNotBlank() -> res.error
    else -> "没有可执行的待办"
}

/** `claim` 返回 `{ok, credit, energy, already_claimed}`。 */
private fun claimMessage(res: ActionResult): String {
    if (res.alreadyClaimed) return "该奖励此前已领取"
    val credit = res.credit ?: 0.0
    val energy = res.energy ?: 0.0
    val parts = buildList {
        if (credit > 0) add("+${creditsText(credit)} 分")
        if (energy > 0) add("+${creditsText(energy)} 能")
    }
    return when {
        parts.isNotEmpty() -> "领取成功：" + parts.joinToString(" / ")
        res.message.isNotBlank() -> res.message
        else -> "领取完成"
    }
}

/** `auto` 返回 `{ok, message, progress_after, skipped}`。 */
private fun autoTaskMessage(res: ActionResult): String = when {
    res.message.isNotBlank() -> res.message
    res.skipped -> "该任务已完成，无需执行"
    res.progressAfter.isNotBlank() -> "执行完成（进度 ${res.progressAfter}）"
    res.ok -> "执行完成"
    else -> "执行失败"
}

/** 读取 JSON 字符串字段（非字符串 / 缺失一律空串）。 */
private fun JsonObject.stringOrEmpty(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** 读取 JSON 整数字段（缺失 / 非数字一律 0）。 */
private fun JsonObject.intOrZero(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0

/** 读取 JSON 数组字段（非数组一律 null）。 */
private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
