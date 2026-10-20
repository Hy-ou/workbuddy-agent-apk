package com.workbuddy2api.panel.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.workbuddy2api.panel.agent.MobileTools
import com.workbuddy2api.panel.data.agent.AgentSession
import com.workbuddy2api.panel.data.agent.AgentStore
import com.workbuddy2api.panel.data.agent.ChatMessage
import com.workbuddy2api.panel.data.agent.OpenAiClient
import com.workbuddy2api.panel.gateway.GatewayStatus
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbTextField
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 原生 Agent 对话页。
 *
 * 状态全部本地持有（会话 id 用 [rememberSaveable]，其余用 [remember]）：
 * - 会话与消息的持久化统一走 [AgentStore]；
 * - 流式回答通过 [OpenAiClient.chatStream] 增量刷新最后一条助手消息；
 * - 开启手机操作且无障碍就绪时，system prompt 追加工具协议，
 *   回复里的 ```json 工具围栏会被执行，结果作为 role=tool 消息追加后继续请求（最多 [MAX_TOOL_ROUNDS] 轮）。
 *
 * @param client 网关未就绪时为 null，输入区会给出提示
 * @param store 会话存储（StateFlow 驱动界面，save 后自动刷新）
 * @param gatewayStatus 本地网关状态，用于顶部状态条与引导卡
 */
@Composable
fun AssistantScreen(
    client: OpenAiClient?,
    store: AgentStore,
    gatewayStatus: GatewayStatus,
    onStartGateway: () -> Unit,
    onRestartGateway: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenGatewayLogs: () -> Unit,
) {
    val c = LocalWbColors.current
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultPrompt = remember(store) { store.defaultSystemPrompt() }

    // ── 会话与消息 ──────────────────────────────────────────────────────
    val sessions by store.sessions.collectAsState()
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    val session: AgentSession? = sessions.firstOrNull { it.id == currentId } ?: sessions.firstOrNull()
    // 已加载到本地 messages 的会话 id：切换会话后才重新从 store 读取，避免流式更新被覆盖。
    var loadedId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }

    // ── 输入 / 流式 / 工具开关 ──────────────────────────────────────────
    var input by rememberSaveable { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var toolMode by rememberSaveable { mutableStateOf(false) }

    // ── 弹层 ────────────────────────────────────────────────────────────
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 从系统无障碍设置返回时重新检查授权状态（isReady 不是 Compose 可观察状态）。
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val toolReady = remember(resumeTick) { MobileTools.isReady(context) }

    // ── 本地操作 ────────────────────────────────────────────────────────

    /** 把消息写回会话（标题未命名时用第一条用户消息自动命名）并持久化。 */
    fun saveMessages(base: AgentSession, next: List<ChatMessage>) {
        val live = store.sessions.value.firstOrNull { it.id == base.id } ?: base
        val firstUser = next.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
        val title = if (live.title.isBlank() || live.title == "新会话") {
            firstUser.take(18).ifBlank { live.title }
        } else {
            live.title
        }
        store.save(
            live.copy(
                title = title,
                messages = next.toMutableList(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 更新最后一条消息（流式输出时就是助手占位消息）。 */
    fun updateLastContent(content: String, pending: Boolean) {
        if (messages.isEmpty()) return
        val next = messages.toMutableList()
        next[next.lastIndex] = next.last().copy(content = content, pending = pending)
        messages = next
    }

    /** 当前会话；一个都没有时创建一个新会话（输入框直接发消息也能用）。 */
    fun ensureSession(): AgentSession {
        session?.let { return it }
        val created = store.newSession(
            model = DEFAULT_AGENT_MODEL,
            systemPrompt = defaultPrompt,
        )
        currentId = created.id
        loadedId = created.id
        messages = emptyList()
        return created
    }

    fun startNewSession() {
        if (streaming) {
            scope.launch { snackbar.showSnackbar("正在生成回答，请先停止") }
            return
        }
        val created = store.newSession(
            model = session?.model ?: DEFAULT_AGENT_MODEL,
            systemPrompt = session?.systemPrompt ?: defaultPrompt,
            title = "新会话",
        )
        currentId = created.id
        loadedId = created.id
        messages = emptyList()
        input = ""
        showSessions = false
    }

    fun selectSession(id: String) {
        if (id == session?.id) {
            showSessions = false
            return
        }
        if (streaming) {
            scope.launch { snackbar.showSnackbar("正在生成回答，请先停止") }
            return
        }
        val target = store.sessions.value.firstOrNull { it.id == id } ?: return
        currentId = id
        loadedId = id
        val cleaned = sanitizePendingMessages(target.messages)
        messages = cleaned
        if (cleaned != target.messages.toList()) saveMessages(target, cleaned)
        showSessions = false
    }

    fun deleteSession(id: String) {
        if (streaming && id == session?.id) {
            scope.launch { snackbar.showSnackbar("正在生成回答，请先停止") }
            return
        }
        val deletingCurrent = id == session?.id
        store.delete(id)
        if (deletingCurrent) {
            currentId = null
            loadedId = null
            messages = emptyList()
        }
    }

    fun selectModel(model: String) {
        val chosen = model.trim().ifBlank { DEFAULT_AGENT_MODEL }
        val active = ensureSession()
        val live = store.sessions.value.firstOrNull { it.id == active.id } ?: active
        store.save(live.copy(model = chosen, updatedAt = System.currentTimeMillis()))
        showModels = false
        scope.launch { snackbar.showSnackbar("已切换模型：$chosen") }
    }

    fun saveSettings(prompt: String) {
        val active = ensureSession()
        val value = prompt.trim().ifBlank { defaultPrompt }
        val live = store.sessions.value.firstOrNull { it.id == active.id } ?: active
        store.save(live.copy(systemPrompt = value, updatedAt = System.currentTimeMillis()))
        showSettings = false
        scope.launch { snackbar.showSnackbar("助手设置已保存") }
    }

    /**
     * 发送一条消息：追加用户消息 + 助手占位消息，流式收集结果；
     * 若回复是工具调用则执行并继续请求（最多 [MAX_TOOL_ROUNDS] 轮）。
     */
    fun send(rawText: String) {
        val content = rawText.trim()
        val agent = client
        if (content.isEmpty() || agent == null || streaming) return

        val active = ensureSession()
        val toolsEnabled = toolMode && MobileTools.isReady(context)
        val systemPrompt = buildSystemPrompt(active.systemPrompt, toolsEnabled)
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(role = "user", content = content, ts = now)
        val firstHistory = messages + userMessage
        messages = firstHistory + ChatMessage(
            role = "assistant",
            content = "",
            ts = now + 1,
            pending = true,
        )
        // 先落盘（含 pending 占位），进程被杀时加载会把它收敛成「已中断」。
        saveMessages(active, messages)
        input = ""
        streaming = true

        requestJob = scope.launch {
            var rounds = 0
            var history = firstHistory
            try {
                while (true) {
                    val answer = streamChat(agent, active.model, history, systemPrompt) { partial ->
                        updateLastContent(partial, pending = true)
                    }
                    val toolJson = if (toolMode && MobileTools.isReady(context)) {
                        extractToolCall(answer)
                    } else {
                        null
                    }
                    if (toolJson != null && rounds < MAX_TOOL_ROUNDS) {
                        // 工具轮：保留模型的 ```json 调用，执行结果作为 tool 消息追加。
                        updateLastContent(answer, pending = false)
                        val result = runCatching { MobileTools.execute(context, toolJson) }
                            .getOrElse { "工具执行失败：${it.message ?: "未知错误"}" }
                        val withTool = messages + ChatMessage(
                            role = "tool",
                            content = result,
                            ts = System.currentTimeMillis(),
                        )
                        messages = withTool
                        saveMessages(active, withTool)
                        rounds++
                        history = withTool
                        messages = withTool + ChatMessage(
                            role = "assistant",
                            content = "",
                            ts = System.currentTimeMillis(),
                            pending = true,
                        )
                        continue
                    }
                    updateLastContent(answer.ifBlank { "（没有返回内容）" }, pending = false)
                    saveMessages(active, messages)
                    break
                }
            } catch (e: CancellationException) {
                // 用户点「停止」：保留已生成内容，标记为已结束。
                messages.lastOrNull()?.let { last ->
                    updateLastContent(last.content.ifBlank { "（已停止）" }, pending = false)
                    saveMessages(active, messages)
                }
                throw e
            } catch (e: Exception) {
                val note = e.message?.trim().takeUnless { it.isNullOrBlank() } ?: "网络请求失败"
                val last = messages.lastOrNull()
                val failed = if (last?.content.isNullOrBlank()) {
                    "请求失败：$note"
                } else {
                    "${last?.content}\n\n【请求失败：$note】"
                }
                updateLastContent(failed, pending = false)
                saveMessages(active, messages)
                // 单独起一个协程弹提示：不让 snackbar 的停留时间拖住 streaming / requestJob 收尾。
                scope.launch { snackbar.showSnackbar("请求失败：$note") }
            } finally {
                streaming = false
                requestJob = null
            }
        }
    }

    // ── 自动滚动到底部 ──────────────────────────────────────────────────
    val listState = rememberLazyListState()

    /** 先滚到末条，再把超出视口的部分补滚掉，保证长回答的底部永远可见。 */
    suspend fun scrollToBottom(animate: Boolean) {
        if (messages.isEmpty()) return
        val index = messages.lastIndex
        if (animate) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull { it.index == index } ?: return
        val overflow = last.offset + last.size - info.viewportEndOffset
        if (overflow > 0) listState.scrollBy(overflow.toFloat())
    }

    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0
    var lastMessageCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.size, lastContentLength) {
        val jump = lastMessageCount == 0 || messages.size - lastMessageCount != 1
        lastMessageCount = messages.size
        scrollToBottom(animate = !streaming && !jump)
    }

    // 会话切换 / 首次进入：从 store 读取消息（pending 消息收敛为「已中断」）。
    LaunchedEffect(session?.id) {
        val target = session
        if (target == null) {
            loadedId = null
            messages = emptyList()
            return@LaunchedEffect
        }
        if (target.id == loadedId) return@LaunchedEffect
        loadedId = target.id
        val cleaned = sanitizePendingMessages(target.messages)
        messages = cleaned
        if (cleaned != target.messages.toList()) saveMessages(target, cleaned)
    }

    // ── 界面 ────────────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        WbTopBar(
            title = "助手",
            subtitle = session?.let { s ->
                "${s.title.ifBlank { "新会话" }} · ${s.model.ifBlank { DEFAULT_AGENT_MODEL }}"
            } ?: "暂无会话",
            actions = {
                IconButton(onClick = { showSessions = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Forum,
                        contentDescription = "会话列表",
                        tint = c.ink2,
                    )
                }
                IconButton(onClick = { startNewSession() }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "新建会话",
                        tint = c.ink2,
                    )
                }
            },
        )

        GatewayStrip(
            status = gatewayStatus,
            clientReady = client != null,
            onStartGateway = onStartGateway,
            onRestartGateway = onRestartGateway,
            onOpenGatewayLogs = onOpenGatewayLogs,
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        AssistantEmptyState(
                            clientReady = client != null,
                            gatewayStatus = gatewayStatus,
                            onStartGateway = onStartGateway,
                            onRestartGateway = onRestartGateway,
                            onOpenGatewayLogs = onOpenGatewayLogs,
                            onSuggest = { prompt ->
                                if (client == null) {
                                    input = prompt
                                    scope.launch {
                                        snackbar.showSnackbar("网关未就绪，请先启动本地网关")
                                    }
                                } else {
                                    send(prompt)
                                }
                            },
                        )
                    }
                } else {
                    items(count = messages.size) { index ->
                        ChatBubble(messages[index])
                    }
                }
            }
        }

        QuickActionsBar(
            onOpenAccounts = onOpenAccounts,
            onOpenLogs = onOpenLogs,
            onOpenGatewayLogs = onOpenGatewayLogs,
        )
        HorizontalDivider(color = c.lineSoft)
        ChatInputBar(
            input = input,
            onInputChange = { input = it },
            canSend = input.isNotBlank() && client != null && !streaming,
            streaming = streaming,
            clientReady = client != null,
            onSend = { send(input) },
            onStop = { requestJob?.cancel() },
            onInputFocus = { scope.launch { scrollToBottom(animate = false) } },
        )
    }

    // ── 底部弹层 ────────────────────────────────────────────────────────
    if (showSessions) {
        AssistantSessionSheet(
            sessions = sessions,
            currentId = session?.id,
            currentModel = session?.model ?: DEFAULT_AGENT_MODEL,
            onDismiss = { showSessions = false },
            onSelect = { selectSession(it) },
            onNew = { startNewSession() },
            onDelete = { deleteSession(it) },
            onOpenModels = {
                showSessions = false
                showModels = true
            },
            onOpenSettings = {
                showSessions = false
                showSettings = true
            },
        )
    }

    if (showModels) {
        AssistantModelSheet(
            client = client,
            currentModel = session?.model ?: DEFAULT_AGENT_MODEL,
            onDismiss = { showModels = false },
            onSelect = { selectModel(it) },
        )
    }

    if (showSettings) {
        AssistantSettingsSheet(
            initialPrompt = session?.systemPrompt ?: defaultPrompt,
            generalPrompt = defaultPrompt,
            toolMode = toolMode,
            toolReady = toolReady,
            onToolModeChange = { enabled ->
                toolMode = enabled
                if (enabled && !MobileTools.isReady(context)) {
                    scope.launch { snackbar.showSnackbar("请先开启无障碍服务，手机操作才能生效") }
                }
            },
            onOpenAccessibility = { MobileTools.openSettings(context) },
            onSave = { saveSettings(it) },
            onOpenAccounts = {
                showSettings = false
                onOpenAccounts()
            },
            onOpenLogs = {
                showSettings = false
                onOpenLogs()
            },
            onOpenGatewayLogs = {
                showSettings = false
                onOpenGatewayLogs()
            },
            onDismiss = { showSettings = false },
        )
    }
}

// ---------------------------------------------------------------------------
// 顶层状态条
// ---------------------------------------------------------------------------

/** 顶部细状态条：本地网关运行中 / 远程连接 / 未启动 / 启动中 / 失败。 */
@Composable
private fun GatewayStrip(
    status: GatewayStatus,
    clientReady: Boolean,
    onStartGateway: () -> Unit,
    onRestartGateway: () -> Unit,
    onOpenGatewayLogs: () -> Unit,
) {
    val c = LocalWbColors.current
    val remote = clientReady && status is GatewayStatus.Stopped

    val dotColor = when (status) {
        is GatewayStatus.Running -> c.ok
        is GatewayStatus.Starting -> c.warn
        is GatewayStatus.Failed -> c.bad
        is GatewayStatus.Stopped -> if (remote) c.accent else c.ink3
    }
    val label = when (status) {
        is GatewayStatus.Running -> "本地网关运行中"
        is GatewayStatus.Starting -> "本地网关启动中…"
        is GatewayStatus.Failed -> "网关启动失败：${status.message.ifBlank { "未知错误" }}"
        is GatewayStatus.Stopped -> if (remote) "远程连接" else "网关未启动"
    }
    val labelColor = when (status) {
        is GatewayStatus.Failed -> c.bad
        is GatewayStatus.Starting -> c.warn
        is GatewayStatus.Stopped -> if (remote) c.accent else c.ink2
        is GatewayStatus.Running -> c.ink2
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .background(c.surface)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (status is GatewayStatus.Running) {
                Mono(text = ":${status.port}", size = 11.5.sp, color = c.ink3)
                Spacer(Modifier.width(6.dp))
            }
            if (status is GatewayStatus.Starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = c.warn,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            when (status) {
                is GatewayStatus.Running, is GatewayStatus.Failed -> StripAction("重启", onRestartGateway)
                is GatewayStatus.Stopped -> if (!clientReady) StripAction("启动", onStartGateway)
                is GatewayStatus.Starting -> Unit
            }
            IconButton(onClick = onOpenGatewayLogs, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Article,
                    contentDescription = "网关日志",
                    tint = c.ink3,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        HorizontalDivider(color = c.lineSoft)
    }
}

/** 状态条右侧的小文字动作（44dp 高触控目标由外层 Row 提供）。 */
@Composable
private fun StripAction(text: String, onClick: () -> Unit) {
    val c = LocalWbColors.current
    Box(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
            color = c.accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// 空会话引导
// ---------------------------------------------------------------------------

/** 空会话：问候语 + 网关引导卡（未就绪时）+ 推荐提问。 */
@Composable
private fun AssistantEmptyState(
    clientReady: Boolean,
    gatewayStatus: GatewayStatus,
    onStartGateway: () -> Unit,
    onRestartGateway: () -> Unit,
    onOpenGatewayLogs: () -> Unit,
    onSuggest: (String) -> Unit,
) {
    val c = LocalWbColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "你好，我是 WorkBuddy 助手",
            style = MaterialTheme.typography.titleMedium,
            color = c.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "可以问我账号池状态、最近失败请求，也可以让我帮你操作手机。",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink3,
            textAlign = TextAlign.Center,
        )

        if (!clientReady) {
            Spacer(Modifier.height(18.dp))
            GatewayGuideCard(
                status = gatewayStatus,
                onStartGateway = onStartGateway,
                onRestartGateway = onRestartGateway,
                onOpenGatewayLogs = onOpenGatewayLogs,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "试试这样问",
            style = MaterialTheme.typography.labelMedium,
            color = c.ink3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FilterChips(
            labels = SUGGESTED_PROMPTS,
            selectedIndex = -1,
            onSelect = { index -> onSuggest(SUGGESTED_PROMPTS[index]) },
        )
    }
}

/** 网关未就绪引导卡：说明 + 启动/重启 + 查看网关日志。 */
@Composable
private fun GatewayGuideCard(
    status: GatewayStatus,
    onStartGateway: () -> Unit,
    onRestartGateway: () -> Unit,
    onOpenGatewayLogs: () -> Unit,
) {
    val c = LocalWbColors.current
    val description = when (status) {
        is GatewayStatus.Running -> "本地网关已运行，助手客户端还在准备中，请稍候；也可以查看网关日志确认状态。"
        is GatewayStatus.Starting -> "本地网关正在启动，请稍候…"
        is GatewayStatus.Failed -> "本地网关启动失败：${status.message.ifBlank { "未知错误" }}。可重启网关，或查看网关日志排查。"
        is GatewayStatus.Stopped -> "启动本机内置网关后即可对话；也可以在「设置」页改用远程服务。"
    }
    val startLabel = when (status) {
        is GatewayStatus.Starting -> "启动中…"
        is GatewayStatus.Failed -> "重启网关"
        else -> "启动本地网关"
    }
    WbCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "助手还没连上服务",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = c.ink3,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status !is GatewayStatus.Running) {
                WbButton(
                    text = startLabel,
                    onClick = if (status is GatewayStatus.Failed) onRestartGateway else onStartGateway,
                    enabled = status !is GatewayStatus.Starting,
                    primary = true,
                )
            }
            WbButton(text = "网关日志", onClick = onOpenGatewayLogs)
        }
    }
}

// ---------------------------------------------------------------------------
// 底部快捷入口与输入区
// ---------------------------------------------------------------------------

/** 输入框上方的运维快捷入口。 */
@Composable
private fun QuickActionsBar(
    onOpenAccounts: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenGatewayLogs: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickAction("账号池", onOpenAccounts)
        QuickAction("运行日志", onOpenLogs)
        QuickAction("网关日志", onOpenGatewayLogs)
    }
}

@Composable
private fun QuickAction(text: String, onClick: () -> Unit) {
    val c = LocalWbColors.current
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
            color = c.ink3,
        )
    }
}

/** 底部输入区：多行输入 + 发送/停止按钮 + 网关提示。 */
@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    streaming: Boolean,
    clientReady: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInputFocus: () -> Unit,
) {
    val c = LocalWbColors.current
    Column(modifier = Modifier.fillMaxWidth().background(c.bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            WbTextField(
                value = input,
                onValueChange = onInputChange,
                label = "输入消息",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 132.dp)
                    .onFocusChanged { state -> if (state.isFocused) onInputFocus() },
                singleLine = false,
                placeholder = "输入消息，支持换行",
            )
            Spacer(Modifier.width(8.dp))
            SendButton(
                streaming = streaming,
                canSend = canSend,
                onSend = onSend,
                onStop = onStop,
            )
        }
        val hint = when {
            !clientReady -> "网关未就绪，请先启动本地网关或连接远程服务"
            streaming -> "正在生成回答，点击停止可中断"
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (!clientReady) c.warn else c.ink3,
                modifier = Modifier.padding(start = 18.dp, end = 16.dp, bottom = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 发送按钮：流式时变成红色停止按钮，否则为 accent 实心发送按钮。 */
@Composable
private fun SendButton(
    streaming: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = LocalWbColors.current
    val background = when {
        streaming -> c.badSoft
        canSend -> c.accent
        else -> c.surface2
    }
    val tint = when {
        streaming -> c.bad
        canSend -> MaterialTheme.colorScheme.onPrimary
        else -> c.ink3
    }
    IconButton(
        onClick = { if (streaming) onStop() else onSend() },
        enabled = streaming || canSend,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background),
    ) {
        Icon(
            imageVector = if (streaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
            contentDescription = if (streaming) "停止生成" else "发送",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}
