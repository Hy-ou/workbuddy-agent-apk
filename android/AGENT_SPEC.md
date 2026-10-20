# WorkBuddy 一体化 APK：网关 + Agent 集成规范

## 目标
单 APK 内同时提供：
1. **本地网关**（Go 服务端跨编译为 Android 可执行文件，跑在 127.0.0.1，App 自己拉起/停止）；
2. **原生 Agent**（流式对话、多会话、模型选择、可选手机操作）；
3. 原有管理面板（总览/账号/任务/日志/设置）。

网关与 Agent 都通过本机 HTTP 通信；不影响远程服务器模式。

## 一、网关（Lead 负责，其他协作者不要改）
- 可执行文件：`app/src/main/jniLibs/arm64-v8a/libwb2api.so`、`x86_64/libwb2api.so`（GOOS=android 交叉编译）。
- 运行目录：`context.filesDir/gateway/`（config.json、auths/、data/）；端口 7863 起自动选择空闲端口。
- `com.workbuddy2api.panel.gateway.GatewayStatus`：
```kotlin
sealed interface GatewayStatus {
    data object Stopped : GatewayStatus
    data class Starting(val message: String) : GatewayStatus
    data class Running(val port: Int, val apiKey: String) : GatewayStatus
    data class Failed(val message: String) : GatewayStatus
}
class LocalGatewayManager {
    val status: StateFlow<GatewayStatus>
    suspend fun start(): GatewayStatus
    fun stop()
    suspend fun restart(): GatewayStatus
    fun logTail(maxLines: Int = 120): String
    companion object { fun get(context: Context): LocalGatewayManager }
}
```
- 本地网关就绪后：`Session.serverUrl = "http://127.0.0.1:<port>"`、`apiKey = <config.json 里的 api_key>`、`local = true`。
- 服务端 API：`/healthz`（无鉴权）、`/v1/models`、`/v1/chat/completions`（Bearer）、`/panel/api/*`。

## 二、Agent 数据层（负责人：agent-core）
```kotlin
// data/agent/AgentModels.kt
@Serializable data class ChatMessage(
    val role: String,           // "user" | "assistant" | "tool"
    val content: String,
    val ts: Long = 0L,
    val pending: Boolean = false,
)
@Serializable data class AgentSession(
    val id: String,
    val title: String,
    val model: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: MutableList<ChatMessage> = mutableListOf(),
)
@Serializable data class ModelOption(val id: String, val label: String)

// data/agent/OpenAiClient.kt
class OpenAiClient(baseUrl: String, apiKey: String) : Closeable {
    suspend fun models(): List<ModelOption>   // GET {base}/v1/models -> data[].id
    /**
     * POST {base}/v1/chat/completions，stream=true。
     * 逐块 emit 增量文本；解析 SSE 行 `data: {...}`，取 choices[0].delta.content
     * （兼容 reasoning_content）；`data: [DONE]` 结束；HTTP 错误抛异常（中文 message）。
     */
    fun chatStream(model: String, messages: List<ChatMessage>, systemPrompt: String): Flow<String>
    override fun close()
}

// data/agent/AgentStore.kt
class AgentStore(context: Context) {
    val sessions: StateFlow<List<AgentSession>>
    fun newSession(model: String, systemPrompt: String, title: String = "新会话"): AgentSession
    fun save(session: AgentSession)         // upsert + 持久化到 filesDir/agent/sessions.json
    fun delete(id: String)
    fun defaultSystemPrompt(): String
}
```
- 持久化用 kotlinx.serialization + 简单 JSON 文件，协程 IO；进程重启后会话仍在。
- `defaultSystemPrompt()` 返回中文通用助手提示词（含“用中文、简洁、代码用围栏”）。

## 三、手机操作（负责人：agent-core，同一人做）
```kotlin
// agent/MobileTools.kt
object MobileTools {
    const val TOOL_PROMPT_ZH: String  // 工具协议说明，注入 system prompt
    fun isReady(context: Context): Boolean          // 无障碍服务已连接
    fun openSettings(context: Context)               // 跳转系统无障碍设置
    /** 解析 {"tool":"screen"|"tap"|"type"|"scroll"|"back"|"home", ...} 执行并返回中文结果 */
    suspend fun execute(context: Context, json: String): String
}
// agent/WbAccessibilityService.kt
class WbAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: WbAccessibilityService? }
}
```
工具协议（由 TOOL_PROMPT_ZH 说明，模型需只输出一段 ```json 围栏）：
- `{"tool":"screen"}` → 当前前台应用 + 可见控件文本列表（去重、限制条数）
- `{"tool":"tap","text":"登录"}` / `{"tool":"tap","x":540,"y":1200}`
- `{"tool":"type","text":"你好"}`（设置 clipboard 后 ACTION_SET_TEXT / 粘贴）
- `{"tool":"scroll","dir":"down"}`、`{"tool":"back"}`、`{"tool":"home"}`
返回统一 `{"ok":true,"result":"..."}` 或 `{"ok":false,"error":"..."}` 的字符串。
无障碍配置文件 `res/xml/wb_accessibility_config.xml`；Service 声明由 Lead 合入 Manifest。
注意：无障碍服务需要在 `AndroidManifest.xml` 里注册（Lead 做），资源你自己写。

## 四、Agent 界面（负责人：agent-ui）
```kotlin
// ui/assistant/AssistantScreen.kt
@Composable fun AssistantScreen(
    client: OpenAiClient?,               // null = 网关未就绪
    store: AgentStore,
    gatewayStatus: GatewayStatus,        // 来自 gateway 包
    onStartGateway: () -> Unit,
    onRestartGateway: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenGatewayLogs: () -> Unit,
)
```
要求（手机优先）：
- 顶部栏「助手」+ 网关状态点（本地运行中/远程/未启动）+ 会话列表按钮 + 新建会话。
- 消息列表：用户右侧气泡（accentSoft）、助手左侧（surface 卡片）；
  流式输出时逐字刷新；底部输入框（多行、发送按钮、流式时显示停止）。
- 会话列表用 ModalBottomSheet：标题/模型/时间、点选切换、长按或滑动删除（用按钮即可）。
- 模型选择：ModalBottomSheet，`client.models()` 的结果；空时回退输入框（默认 `global:deepseek-v4.1-flash`）。
- 设置弹层：system prompt 编辑 + 三个预设（通用/代码/运维）+ 手机操作开关（默认关）。
  开启时必须检查 `MobileTools.isReady`，未授权则提示并跳系统设置。
- 手机操作循环：若开启且 `MobileTools.isReady`，system prompt 追加 `MobileTools.TOOL_PROMPT_ZH`；
  助手回复中若含 ```json {"tool":...} ``` 围栏 → 调 `MobileTools.execute`，把结果以
  role="tool" 消息追加并继续请求（最多 4 轮）。
- 网关未就绪时：显示引导卡（启动本地网关 / 去设置连接远程）；网关 Failed 显示错误 + 重启。
- 底部快捷入口：账号池、运行日志、网关日志（弹层显示 logTail）。
- 全部中文、颜色只用 `LocalWbColors`/MaterialTheme、数字用 `WbFontFamily`（`CompositionLocalProvider`? 直接用 `FontFamily.Monospace`）。

## 五、集成（Lead 负责，勿改）
- `ui/shell/AppShell.kt` 新增底部 Tab「助手」；`AppViewModel` 提供 `agentClient`、`agentStore`、网关控制。
- `ConnectScreen` 增加「本地网关」入口；`SettingsScreen` 增加网关控制卡。
- `AndroidManifest.xml` / `build.gradle.kts` 由 Lead 修改。

## 六、约束
- Kotlin 2.0.21 / Compose BOM 2024.10.01 / okhttp 4.12 / kotlinx-serialization 1.7.3 / 协程 1.9。
- 不要运行 gradle（Lead 统一编译）；不要改其他协作者文件；文件名/包名严格按上面。
- 工具调用可能被 429/503 限流：失败就重试同一调用。
