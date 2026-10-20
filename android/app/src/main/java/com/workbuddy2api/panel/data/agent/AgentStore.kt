package com.workbuddy2api.panel.data.agent

import android.content.Context
import com.workbuddy2api.panel.data.WbJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * Agent 会话仓库：内存 [sessions] + 磁盘单文件持久化。
 *
 * - 落盘位置：`filesDir/agent/sessions.json`，写完先写 `.tmp` 再改名（原子替换），
 *   避免进程被杀时留下半截 JSON；
 * - 全部写盘走 [Dispatchers.IO] 的独立协程 + [Mutex] 串行化，调用方（界面）不会被 IO 卡住；
 * - 初始化时同步读取（文件很小，只读一次），保证首帧就能拿到会话列表；
 * - 文件损坏时先备份成 `sessions.corrupt-<时间戳>.json`，再以空列表启动，绝不覆盖原始数据；
 * - 列表永远按 [AgentSession.updatedAt] 倒序，最近用过的会话排最前。
 */
class AgentStore(context: Context) {

    companion object {
        /** 模型列表为空时界面回退用的默认模型。 */
        const val DEFAULT_MODEL = "global:deepseek-v4.1-flash"

        /** 新会话的默认标题。 */
        const val NEW_SESSION_TITLE = "新会话"

        private const val DIR_NAME = "agent"
        private const val FILE_NAME = "sessions.json"

        private val SERIALIZER = ListSerializer(AgentSession.serializer())

        /** 通用助手提示词：中文、简洁、代码用围栏。 */
        private val DEFAULT_SYSTEM_PROMPT = """
你是 WorkBuddy 手机助手，运行在 Android 上的 WorkBuddy 客户端里。
请遵守以下规则：
1. 始终使用简体中文回答，语气自然、直接，不啰嗦。
2. 先给结论，再给必要细节；一两句话能说清就不要展开成长篇大论。
3. 代码一律放在带语言标记的 Markdown 围栏里（例如 kotlin），并保证可以直接运行。
4. 不确定的事情要明确说「不确定」，不要编造；涉及金钱、删除、发送消息等不可逆操作时先提醒用户。
5. 用户只发来简短指令时，按最合理的理解直接完成，不要反问一串问题。
6. 需要分步骤时用简短的编号列表，避免过度使用标题和表格。
        """.trimIndent()
    }

    private val dir: File = File(context.applicationContext.filesDir, DIR_NAME)
    private val file: File = File(dir, FILE_NAME)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())

    /** 会话列表，已按最近更新时间倒序。 */
    val sessions: StateFlow<List<AgentSession>> = _sessions.asStateFlow()

    init {
        load()
    }

    /** 新建会话：立即插入列表并持久化，返回的对象可直接用于界面。 */
    fun newSession(
        model: String,
        systemPrompt: String,
        title: String = NEW_SESSION_TITLE,
    ): AgentSession {
        val now = System.currentTimeMillis()
        val session = AgentSession(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifEmpty { NEW_SESSION_TITLE },
            model = model.trim().ifEmpty { DEFAULT_MODEL },
            systemPrompt = systemPrompt,
            createdAt = now,
            updatedAt = now,
        )
        _sessions.value = (listOf(session) + _sessions.value).sortedByDescending { it.updatedAt }
        persist()
        return session
    }

    /**
     * 保存/更新会话（按 id upsert）。
     *
     * [AgentSession.updatedAt] 取「原值」与「当前时间」的较大者，
     * 这样界面只要把消息追加进 messages 再调用 save，排序就自动正确。
     */
    fun save(session: AgentSession) {
        val now = System.currentTimeMillis()
        val stored = session.copy(updatedAt = maxOf(session.updatedAt, now))
        val current = _sessions.value
        val next = if (current.any { it.id == stored.id }) {
            current.map { if (it.id == stored.id) stored else it }
        } else {
            current + stored
        }
        _sessions.value = next.sortedByDescending { it.updatedAt }
        persist()
    }

    /** 删除会话（不存在时静默返回）。 */
    fun delete(id: String) {
        val current = _sessions.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return
        _sessions.value = next
        persist()
    }

    /** 通用助手提示词（中文、简洁、代码用围栏）。 */
    fun defaultSystemPrompt(): String = DEFAULT_SYSTEM_PROMPT

    // ── 持久化 ────────────────────────────────────────────────────────

    /** 初始化时同步加载；文件不存在或为空视为首次启动。 */
    private fun load() {
        if (!file.isFile || file.length() == 0L) return
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrBlank()) return
        val decoded = runCatching { WbJson.decodeFromString(SERIALIZER, text) }.getOrNull()
        if (decoded == null) {
            // 损坏的 JSON：备份现场，从空列表启动，绝不静默覆盖用户数据
            runCatching {
                if (!dir.isDirectory) dir.mkdirs()
                file.copyTo(File(dir, "sessions.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            }
            return
        }
        _sessions.value = decoded.sortedByDescending { it.updatedAt }
    }

    /** 异步落盘（串行 + 原子替换），失败只记不抛。 */
    private fun persist() {
        scope.launch {
            writeLock.withLock {
                val snapshot = _sessions.value
                runCatching { writeAtomically(snapshot) }
            }
        }
    }

    private fun writeAtomically(list: List<AgentSession>) {
        if (!dir.isDirectory) dir.mkdirs()
        val text = WbJson.encodeToString(SERIALIZER, list)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // 少数文件系统不支持覆盖式 rename：退化为直接覆盖写
            file.writeText(text)
            tmp.delete()
        }
    }
}
