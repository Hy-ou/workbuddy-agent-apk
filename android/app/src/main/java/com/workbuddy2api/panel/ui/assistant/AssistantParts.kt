package com.workbuddy2api.panel.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy2api.panel.agent.MobileTools
import com.workbuddy2api.panel.data.WbHttpException
import com.workbuddy2api.panel.data.agent.AgentSession
import com.workbuddy2api.panel.data.agent.ChatMessage
import com.workbuddy2api.panel.data.agent.ModelOption
import com.workbuddy2api.panel.data.agent.OpenAiClient
import com.workbuddy2api.panel.ui.components.ConfirmDialog
import com.workbuddy2api.panel.ui.components.EmptyState
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbTextField
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbMono
import com.workbuddy2api.panel.util.agoText
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * 助手页的「非骨架」部分：常量、流式与工具调用协议、消息气泡、三个底部弹层。
 *
 * 所有组件都是「哑」的：状态由 AssistantScreen 持有，这里只负责展示与回调，
 * 方便单独预览与后续替换（例如接入 ViewModel 时）。
 */

// ---------------------------------------------------------------------------
// 常量与协议
// ---------------------------------------------------------------------------

/** 模型列表拿不到时的回退模型；与网关 /v1/models 的默认项保持一致。 */
internal const val DEFAULT_AGENT_MODEL = "global:deepseek-v4.1-flash"

/** 单次提问最多允许的「工具调用 → 再请求」轮数，防止模型陷入死循环。 */
internal const val MAX_TOOL_ROUNDS = 4

/** 空会话时的推荐提问（点击直接发送）。 */
internal val SUGGESTED_PROMPTS = listOf(
    "介绍我的账号池状态",
    "帮我看看最近失败请求",
    "读一下当前屏幕",
)

/** 系统提示词预设：通用走 AgentStore 的默认提示词，其余是本地固定文案。 */
internal data class PromptPreset(val label: String, val prompt: String)

internal fun promptPresets(general: String): List<PromptPreset> {
    val fallback = "你是一个乐于助人的中文 AI 助手。回答用中文、简洁直接；代码使用 Markdown 围栏。"
    return listOf(
        PromptPreset("通用", general.trim().ifBlank { fallback }),
        PromptPreset(
            "代码",
            "你是一名资深 Android / Kotlin 工程师，熟悉 Jetpack Compose、协程与 OkHttp。" +
                "回答用中文：先给结论，再给可运行的代码。代码必须放在带语言标记的 Markdown 围栏中，" +
                "并注明文件路径；不确定的地方明确说明，不要编造 API。",
        ),
        PromptPreset(
            "运维",
            "你是一名严谨的 SRE / 运维工程师。回答用中文：先给判断依据，再给可执行命令与检查步骤，" +
                "最后给回滚方案。命令与配置放在 Markdown 围栏中；涉及危险操作（删除、重启、改配置）" +
                "必须先提示风险并建议备份。",
        ),
    )
}

/** 组合最终 system prompt；开启手机操作且无障碍就绪时追加工具协议。 */
internal fun buildSystemPrompt(base: String, toolsEnabled: Boolean): String {
    val core = base.trim().ifBlank {
        "你是一个乐于助人的中文 AI 助手。回答用中文、简洁直接；代码使用 Markdown 围栏。"
    }
    if (!toolsEnabled) return core
    val tool = MobileTools.TOOL_PROMPT_ZH.trim()
    if (tool.isEmpty() || core.contains(tool)) return core
    return core + "\n\n" + tool
}

/** 429 / 503 是上游最常见的瞬时限流，重试同一请求即可（最多两次）。 */
private fun isRetryable(e: Throwable): Boolean {
    if (e is WbHttpException && (e.status == 429 || e.status == 503)) return true
    val msg = e.message.orEmpty()
    return msg.contains("429") || msg.contains("503")
}

/**
 * 收集一次流式回答，返回完整文本；每收到一块通过 [onText] 回传「当前累计文本」。
 *
 * - 中途遇到 429/503 会静默重试（重置累计文本，避免重复）；
 * - 协程取消（用户点停止）原样抛出，由调用方标记消息为已中断。
 */
internal suspend fun streamChat(
    client: OpenAiClient,
    model: String,
    messages: List<ChatMessage>,
    systemPrompt: String,
    onText: (String) -> Unit,
): String {
    var attempt = 0
    while (true) {
        val acc = StringBuilder()
        onText("")
        try {
            client.chatStream(model, messages, systemPrompt).collect { delta ->
                acc.append(delta)
                onText(acc.toString())
            }
            return acc.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt < 2 && isRetryable(e)) {
                attempt++
                delay(700L * attempt)
                continue
            }
            throw e
        }
    }
}

/** 匹配 Markdown 代码围栏（语言标记可省略）；模型可能输出 ```JSON，大小写不敏感。 */
private val JSON_FENCE = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

/**
 * 从完整回复里提取工具调用 JSON。
 *
 * 只有「围栏内容能解析成 JSON 对象、且带非空字符串 tool 字段」才算工具调用；
 * 普通代码围栏、解释性文字一律返回 null（调用方按正常回答结束）。
 */
internal fun extractToolCall(text: String): String? {
    if (!text.contains("```")) return null
    for (match in JSON_FENCE.findAll(text)) {
        val raw = match.groupValues[1].trim()
        if (raw.isEmpty()) continue
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: continue
        val obj = element as? JsonObject ?: continue
        val tool = (obj["tool"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (tool.isEmpty()) continue
        return obj.toString()
    }
    return null
}

/** 毫秒时间戳 → 相对时间；未记录时间（0）显示 `—`。 */
internal fun agoFromMillis(ms: Long): String {
    if (ms <= 0L) return "—"
    return runCatching { agoText(Instant.ofEpochMilli(ms).toString()) }.getOrDefault("—")
}

/** 进程被杀时可能留下 pending 消息；加载会话时把它们收敛成「已中断」。 */
internal fun sanitizePendingMessages(list: List<ChatMessage>): List<ChatMessage> = list.map { message ->
    if (message.pending) {
        message.copy(pending = false, content = message.content.ifBlank { "（上次回复已中断）" })
    } else {
        message
    }
}

// ---------------------------------------------------------------------------
// 消息气泡
// ---------------------------------------------------------------------------

/** 单条消息：按 role 分发到用户 / 助手 / 工具三种气泡。 */
@Composable
internal fun ChatBubble(message: ChatMessage) {
    when (message.role.lowercase()) {
        "user" -> UserBubble(message.content)
        "tool" -> ToolBubble(message.content)
        else -> AssistantBubble(message)
    }
}

/** 用户消息：右对齐、accentSoft 底、ink 文字。 */
@Composable
private fun UserBubble(text: String) {
    val c = LocalWbColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(c.accentSoft)
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
            }
        }
    }
}

/** 助手消息：左对齐、raise 卡片 + 细描边，正文可选中，支持代码围栏。 */
@Composable
private fun AssistantBubble(message: ChatMessage) {
    val c = LocalWbColors.current
    val shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = 328.dp)
                .clip(shape)
                .background(c.raise)
                .border(1.dp, c.lineSoft, shape)
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            if (message.pending && message.content.isBlank()) {
                ThinkingRow()
            } else {
                SelectionContainer {
                    AssistantContent(
                        content = if (message.pending) message.content + "▍" else message.content,
                    )
                }
            }
        }
    }
}

/** 「正在思考」占位：小转圈 + 文案。 */
@Composable
private fun ThinkingRow() {
    val c = LocalWbColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            color = c.accent,
            strokeWidth = 1.5.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "正在思考…",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink3,
        )
    }
}

/** 工具结果：小号等宽 ink3 行，默认折叠为 4 行，点按展开 / 收起。 */
@Composable
private fun ToolBubble(content: String) {
    val c = LocalWbColors.current
    var expanded by remember { mutableStateOf(false) }
    val body = content.ifBlank { "（无返回内容）" }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(
            text = "🛠 工具结果：$body",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = WbMono,
                fontSize = 11.5.sp,
            ),
            color = c.ink3,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface2)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 助手正文：纯文本 + 代码围栏分段渲染
// ---------------------------------------------------------------------------

private data class MdSegment(val text: String, val code: Boolean)

/** 常见代码围栏语言标记；命中时去掉首行，避免把 `kotlin` 当代码显示。 */
private val CODE_LANGS = setOf(
    "kotlin", "kts", "java", "xml", "json", "js", "javascript", "ts", "typescript",
    "python", "py", "bash", "sh", "shell", "zsh", "sql", "go", "gradle", "groovy",
    "yaml", "yml", "toml", "ini", "properties", "http", "diff", "text", "plaintext",
)

/** 把正文按 ``` 围栏切成「普通文本 / 代码」片段；未闭合的围栏按代码处理。 */
private fun splitFenced(raw: String): List<MdSegment> {
    if (!raw.contains("```")) return listOf(MdSegment(raw, false))
    val out = mutableListOf<MdSegment>()
    var cursor = 0
    while (cursor < raw.length) {
        val start = raw.indexOf("```", cursor)
        if (start < 0) {
            out += MdSegment(raw.substring(cursor), false)
            break
        }
        if (start > cursor) out += MdSegment(raw.substring(cursor, start), false)
        val end = raw.indexOf("```", start + 3)
        if (end < 0) {
            out += MdSegment(stripCodeLang(raw.substring(start + 3)), true)
            break
        }
        out += MdSegment(stripCodeLang(raw.substring(start + 3, end)), true)
        cursor = end + 3
    }
    return out.filter { it.text.isNotBlank() || it.code }
}

/** 去掉围栏首行的语言标记（只认白名单，避免误删单行代码）。 */
private fun stripCodeLang(body: String): String {
    val text = body.trimStart('\n')
    val firstNl = text.indexOf('\n')
    if (firstNl < 0) return text.trim()
    val firstLine = text.substring(0, firstNl).trim().lowercase()
    return if (firstLine in CODE_LANGS) text.substring(firstNl + 1).trimEnd() else text.trimEnd()
}

/** 渲染助手正文：普通文本用正文字体，代码块用 surface2 底 + 等宽小字（保留换行）。 */
@Composable
private fun AssistantContent(content: String) {
    val c = LocalWbColors.current
    val segments = remember(content) { splitFenced(content) }
    if (segments.isEmpty()) {
        Text(text = content, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        segments.forEach { segment ->
            if (segment.code) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(c.surface2)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = WbMono,
                            fontSize = 12.sp,
                        ),
                        color = c.ink2,
                    )
                }
            } else {
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 会话列表弹层
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSessionSheet(
    sessions: List<AgentSession>,
    currentId: String?,
    currentModel: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = LocalWbColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingDelete by remember { mutableStateOf<AgentSession?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "会话",
                    style = MaterialTheme.typography.titleLarge,
                    color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                WbButton(text = "新建会话", onClick = onNew, primary = true)
            }
            Spacer(Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                EmptyState(title = "还没有会话", subtitle = "新建一个会话，开始和助手对话")
            } else {
                sessions.sortedByDescending { it.updatedAt }.forEach { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == currentId,
                        onClick = { onSelect(session.id) },
                        onDelete = { pendingDelete = session },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = c.lineSoft)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "当前模型",
                style = MaterialTheme.typography.labelSmall,
                color = c.ink3,
            )
            Spacer(Modifier.height(3.dp))
            Mono(currentModel.ifBlank { DEFAULT_AGENT_MODEL }, size = 12.sp, color = c.ink2)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WbButton(
                    text = "更换模型",
                    onClick = onOpenModels,
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
                WbButton(
                    text = "助手设置",
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除会话",
            text = "确定删除「${target.title.ifBlank { "未命名会话" }}」？删除后无法恢复。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDelete(target.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SessionRow(
    session: AgentSession,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalWbColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.background(c.accentSoft) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    StatusChip(text = "当前", tone = Tone.Accent)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${session.model.ifBlank { "—" }} · ${agoFromMillis(session.updatedAt)}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = WbMono,
                    fontSize = 11.5.sp,
                ),
                color = c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "删除会话",
                tint = c.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 模型选择弹层
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantModelSheet(
    client: OpenAiClient?,
    currentModel: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val c = LocalWbColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val loader = rememberLoader(client) { client?.models().orEmpty() }
    var custom by rememberSaveable { mutableStateOf(currentModel.ifBlank { DEFAULT_AGENT_MODEL }) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleLarge,
                color = c.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "列表来自服务端 /v1/models；不可用时可直接输入模型 ID。",
                style = MaterialTheme.typography.bodySmall,
                color = c.ink3,
            )
            Spacer(Modifier.height(10.dp))

            if (client == null) {
                Text(
                    text = "网关未就绪，无法获取模型列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.warn,
                )
            } else {
                when (val state = loader.state) {
                    is Loadable.Loading -> LoadingBox()
                    is Loadable.Failed -> ErrorBox(state.message) { loader.refresh() }
                    is Loadable.Ready -> {
                        if (state.data.isEmpty()) {
                            Text(
                                text = "服务端没有返回模型列表，可手动输入模型 ID。",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.ink3,
                            )
                        } else {
                            state.data.forEach { option ->
                                ModelRow(
                                    option = option,
                                    selected = option.id == currentModel,
                                    onClick = { onSelect(option.id) },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = c.lineSoft)
            Spacer(Modifier.height(14.dp))
            Text(
                text = "手动输入模型 ID",
                style = MaterialTheme.typography.labelSmall,
                color = c.ink3,
            )
            Spacer(Modifier.height(6.dp))
            WbTextField(
                value = custom,
                onValueChange = { custom = it },
                label = "模型 ID",
                placeholder = DEFAULT_AGENT_MODEL,
            )
            Spacer(Modifier.height(10.dp))
            WbButton(
                text = "使用该模型",
                onClick = { onSelect(custom.trim().ifBlank { DEFAULT_AGENT_MODEL }) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
            )
        }
    }
}

@Composable
private fun ModelRow(
    option: ModelOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalWbColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.background(c.accentSoft) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label.ifBlank { option.id },
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = option.id,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = WbMono,
                    fontSize = 11.5.sp,
                ),
                color = c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "当前模型",
                tint = c.accent,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 助手设置弹层
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSettingsSheet(
    initialPrompt: String,
    generalPrompt: String,
    toolMode: Boolean,
    toolReady: Boolean,
    onToolModeChange: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onSave: (String) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenGatewayLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalWbColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presets = remember(generalPrompt) { promptPresets(generalPrompt) }
    var prompt by rememberSaveable { mutableStateOf(initialPrompt) }
    var presetIndex by remember {
        mutableIntStateOf(presets.indexOfFirst { it.prompt == initialPrompt })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "助手设置",
                style = MaterialTheme.typography.titleLarge,
                color = c.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "系统提示词会注入到每次对话，决定助手的角色与回答风格。",
                style = MaterialTheme.typography.bodySmall,
                color = c.ink3,
            )
            Spacer(Modifier.height(12.dp))
            WbTextField(
                value = prompt,
                onValueChange = {
                    prompt = it
                    presetIndex = -1
                },
                label = "系统提示词",
                modifier = Modifier.heightIn(min = 120.dp, max = 200.dp),
                singleLine = false,
            )
            Spacer(Modifier.height(8.dp))
            FilterChips(
                labels = presets.map { it.label },
                selectedIndex = presetIndex,
                onSelect = { index ->
                    presetIndex = index
                    prompt = presets[index].prompt
                },
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = c.lineSoft)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手机操作",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "允许助手读取屏幕、点击与输入（需要无障碍权限）",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ink3,
                    )
                }
                Switch(checked = toolMode, onCheckedChange = onToolModeChange)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (toolReady) c.ok else c.warn),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (toolReady) {
                        "无障碍服务已连接，手机操作可用"
                    } else {
                        "未开启无障碍服务，手机操作不可用"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (toolReady) c.ok else c.warn,
                    modifier = Modifier.weight(1f),
                )
                if (!toolReady) {
                    WbButton(text = "去开启", onClick = onOpenAccessibility)
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = c.lineSoft)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "快捷入口",
                style = MaterialTheme.typography.labelSmall,
                color = c.ink3,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WbButton(
                    text = "账号池",
                    onClick = onOpenAccounts,
                    modifier = Modifier.weight(1f),
                )
                WbButton(
                    text = "运行日志",
                    onClick = onOpenLogs,
                    modifier = Modifier.weight(1f),
                )
                WbButton(
                    text = "网关日志",
                    onClick = onOpenGatewayLogs,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(18.dp))
            WbButton(
                text = "保存设置",
                onClick = { onSave(prompt) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
            )
        }
    }
}
