# WorkBuddy Android 客户端 —— 共享实现规范（所有协作者必读）

## 0. 目标
把 Go 服务端的 Web 面板改造成**原生 Android App**（Kotlin + Jetpack Compose + Material3），
手机优先布局：底部 5 个 Tab（总览 / 账号 / 任务 / 日志 / 设置），卡片化、单列、可下拉刷新、
底部弹层详情、边缘到边缘（edge-to-edge）。**不改动 Go 服务端**，App 通过 `/panel/api/*` 通信。

## 1. 工程位置与构建
- 工程根：`android/`（Gradle 8.10.2，AGP 8.7.3，Kotlin 2.0.21，compileSdk 35，minSdk 24）
- 包名：`com.workbuddy2api.panel`
- 构建：`gradle -p android assembleDebug`（工作目录 `D:\Projects\workbuddy2api-panel`）
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`
- 不要修改 `android/app/build.gradle.kts`、`settings.gradle.kts`、`AndroidManifest.xml`，
  如需新依赖先告知 Lead。

## 2. 目录与文件所有权（不要越界改别人的文件）
```
data/        Models*.kt  WbApi.kt  Repository.kt  DemoRepository.kt  SettingsStore.kt  AppViewModel.kt
util/        Format.kt
ui/state/    Loader.kt
ui/components/ Common.kt
ui/theme/    Color.kt Type.kt WbColors.kt Theme.kt（已完成，不要改）
ui/shell/    AppShell.kt
ui/connect/  ConnectScreen.kt
ui/overview/ OverviewScreen.kt
ui/accounts/ AccountsScreen.kt AccountDetailSheet.kt
ui/tasks/    TasksScreen.kt
ui/logs/     LogsScreen.kt
ui/settings/ SettingsScreen.kt ConfigScreen.kt UsageScreen.kt PackagesScreen.kt ProbesScreen.kt VouchersScreen.kt
```

## 3. 设计语言（必须遵守）
- 颜色只从 `LocalWbColors.current` 取（bg/surface/surface2/raise/line/lineSoft/ink/ink2/ink3/
  accent/accentSoft/ok/okSoft/warn/warnSoft/bad/badSoft）；强调色只用于交互与当前态。
- 字体：`MaterialTheme.typography`；所有数字/UID/耗时用 `FontFamily.Monospace`（`WbMono`）。
- 间距：屏幕左右 16dp；卡片圆角 16dp、内边距 14dp；卡片间距 12dp；触控目标 ≥ 44dp。
- 手机布局：单列；不要横向表格；宽表格改「标签 + 值」两行式；统计用 2 列小卡片网格。
- 状态语义：健康=ok 绿、冷却=warn 黄、禁用/错误=bad 红、暂停/中性=ink3 灰。
- 空状态、加载骨架、错误重试必须实现；所有异步操作要防重复点击。
- 文案中文，简洁；技术值（model id、uid）保留原文。
- 深色为默认，支持浅色；不要写死颜色。

## 4. 数据层接口（严格按签名实现，UI 依赖）
```kotlin
// data/ModelsMisc.kt
@Serializable data class SchoolVoucherAccount(uid,nickname,realm,vouchers: List<SchoolVoucher>?,error)
@Serializable data class SchoolVoucher(grant_id: Long, draw_uuid, sku_code, prize_name, code, valid_from, valid_to, granted_at)
@Serializable data class SchoolVouchersPage(accounts: List<SchoolVoucherAccount>)
@Serializable data class ConfigSaveResult(ok: Boolean, error: String = "", restart_required: List<String>? = null)
@Serializable data class QueueStartResult(ok: Boolean = false, started: Boolean = false, total: Int = 0, seq: Long = 0, message: String = "", error: String = "")

enum class AccountAction(val path: String, val label: String) {
    CHECKIN("checkin","签到"), BALANCE("balance","刷新余额"), REVIVE("revive","解冻"),
    DISABLE("disable","禁用"), PAUSE("pause","暂停选号"), RESUME("resume","恢复选号"), REMOVE("remove","移除")
}
enum class BulkAction(val path: String, val label: String) {
    CHECKIN_ALL("checkin_all","全部签到"), TRAVEL_ALL("travel_all","全部旅行"),
    ACTIVITY_ALL("activity_all","全部活动"), KEEPALIVE_ALL("keepalive_all","全部保活"),
    BALANCE_ALL("balance_all","全部刷新余额")
}
```
```kotlin
// data/Repository.kt
interface WbRepository {
    val serverLabel: String                       // 展示用，如 "127.0.0.1:7863"
    val isDemo: Boolean get() = false
    suspend fun overview(): Overview
    suspend fun logs(): List<LogEntry>
    suspend fun requestMetrics(): RequestMetrics
    suspend fun requestLogs(limit: Int = 200, outcome: String? = null, from: Long? = null, to: Long? = null): RequestLogsPage
    suspend fun models(): List<ModelInfo>
    suspend fun accountTasks(uid: String): AccountTasks
    suspend fun acceptTasks(uid: String, codes: List<String>): ActionResult
    suspend fun acceptAllTasks(uid: String): JsonObject
    suspend fun claimTask(uid: String, code: String): ActionResult
    suspend fun autoTask(uid: String, code: String): ActionResult
    suspend fun autoAllTasks(uid: String): JsonObject
    suspend fun scanAll(): ScanResult
    suspend fun runQueue(concurrency: Int = 2): QueueStartResult
    suspend fun taskQueue(): QueueStatus
    suspend fun schoolVouchers(): SchoolVouchersPage
    suspend fun packages(): List<PackageAccount>
    suspend fun usage(hours: Int? = null): UsageSnapshot
    suspend fun modelProbes(): ModelProbes
    suspend fun config(): ConfigEnvelope
    suspend fun saveConfig(patch: JsonObject): ConfigSaveResult
    suspend fun loginStart(realm: String): LoginStart
    suspend fun loginPoll(state: String): LoginPoll
    suspend fun accountAction(uid: String, action: AccountAction): ActionResult
    suspend fun bulkAction(action: BulkAction): ActionResult
}
class WbHttpException(val status: Int, val body: String, override val message: String) : Exception(message)
fun normalizeServerUrl(input: String): String   // "1.2.3.4:7863" -> "http://1.2.3.4:7863"，去掉尾部 /
fun apiBase(url: String): String                // -> url + "/panel/api"
```
```kotlin
// data/WbApi.kt
class WbApi(baseUrl: String, apiKey: String) : Closeable {
    suspend fun get(path: String): String
    suspend fun post(path: String, json: String? = null): String
    override fun close()
}
// 401 -> WbHttpException(401, body, "API Key 无效")
// 其他非 2xx -> 解析 {"ok":false,"error":...} 的 error；非 JSON 用 "HTTP <status>"
// 网络异常 -> IOException，中文 message（"无法连接服务器" / "连接超时"）
```
```kotlin
// data/SettingsStore.kt
data class Session(val serverUrl: String = "", val apiKey: String = "", val demo: Boolean = false,
                   val themeMode: String = "dark", val refreshSec: Int = 5)
class SettingsStore(context: Context) {
    val flow: Flow<Session>
    suspend fun load(): Session
    suspend fun save(s: Session)
}
```
```kotlin
// data/AppViewModel.kt
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val session: StateFlow<Session?>          // null = 首启未配置
    val repo: StateFlow<WbRepository?>        // 已连接时的仓库（demo 时为 DemoWbRepository）
    val connecting: StateFlow<Boolean>
    val connectError: StateFlow<String?>
    fun connect(url: String, key: String)     // 内部调 overview 校验，成功保存 Session
    fun enterDemo()
    fun disconnect()
    fun setTheme(mode: String)
    fun setRefresh(sec: Int)
}
```

## 5. UI 基础设施（严格按签名实现）
```kotlin
// util/Format.kt
fun parseIso(s: String?): Instant?                 // "0001-01-01..." 返回 null；容错毫秒/纳秒
fun agoText(s: String?): String                    // "刚刚" / "3 分钟前" / "2 小时前" / "—"
fun remainText(seconds: Long): String              // "7 秒" / "12 分钟" / "1 小时 5 分"
fun uptimeText(sec: Long): String                  // "2 天 3 小时" / "12 分钟"
fun compactNum(n: Long): String                    // 1234 -> "1,234"；1234567 -> "1.23M"
fun compactNum(d: Double): String
fun creditsText(d: Double): String                 // 整数无小数点；-1 -> "不限"
fun percentText(d: Double): String                 // "94.2%"
fun bytesText(b: Long): String                     // "1.7 MB"
fun msText(ms: Long): String                       // "3.3 s" / "820 ms"
fun shortUid(uid: String): String                  // 前 8 位
fun maskKey(key: String): String                   // "sk-****abcd"
```
```kotlin
// ui/state/Loader.kt
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val data: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}
@Stable class Loader<T>(private val scope: CoroutineScope, private val load: suspend () -> T) {
    var state: Loadable<T> by mutableStateOf(Loadable.Loading); private set
    var refreshing: Boolean by mutableStateOf(false); private set
    fun refresh(quiet: Boolean = false)
    fun set(data: T)
}
@Composable fun <T> rememberLoader(vararg keys: Any?, load: suspend () -> T): Loader<T>
@Composable fun <T> rememberPollingLoader(intervalMs: Long, enabled: Boolean = true, vararg keys: Any?, load: suspend () -> T): Loader<T>
```
```kotlin
// ui/components/Common.kt
enum class Tone { Neutral, Ok, Warn, Bad, Accent }
val LocalSnackbar: ProvidableCompositionLocal<SnackbarHostState>
@Composable fun WbCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)
@Composable fun SectionHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null)
@Composable fun StatTile(label: String, value: String, unit: String? = null, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null)
@Composable fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier)
@Composable fun RealmBadge(realm: String)   // cn -> "国内" 灰；global -> "国际" 蓝
@Composable fun EmptyState(title: String, subtitle: String? = null, actionText: String? = null, onAction: (() -> Unit)? = null)
@Composable fun LoadingBox(modifier: Modifier = Modifier)
@Composable fun ErrorBox(message: String, onRetry: () -> Unit)
@Composable fun WbTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {})
@Composable fun ConfirmDialog(title: String, text: String, confirmText: String = "确定", destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit)
@Composable fun InfoRow(label: String, value: String, mono: Boolean = false, valueColor: Color? = null)
@Composable fun Mono(text: String, modifier: Modifier = Modifier, size: TextUnit = 13.sp, color: Color = LocalWbColors.current.ink2)
@Composable fun WbProgress(value: Float, tone: Tone = Tone.Accent, modifier: Modifier = Modifier)  // value 0..1
@Composable fun FilterChips(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit)
@Composable fun WbPullRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)
@Composable fun WbButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, tone: Tone = Tone.Neutral)
@Composable fun WbTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, password: Boolean = false, placeholder: String? = null, keyboardType: KeyboardType = KeyboardType.Text)
@Composable fun StatGrid(items: List<StatTileData>, columns: Int = 2)
data class StatTileData(val label: String, val value: String, val unit: String? = null, val tone: Tone = Tone.Neutral)
```
`WbPullRefresh` 内部用 material3 `PullToRefreshBox`（加 `@OptIn(ExperimentalMaterial3Api::class)`）。

## 6. 屏幕契约（shell 只提供底部导航；每个屏幕自带顶部栏）
```kotlin
@Composable fun ConnectScreen(vm: AppViewModel)
@Composable fun OverviewScreen(repo: WbRepository, onGoAccounts: () -> Unit, onGoLogs: () -> Unit)
@Composable fun AccountsScreen(repo: WbRepository)
@Composable fun TasksScreen(repo: WbRepository)
@Composable fun LogsScreen(repo: WbRepository)
@Composable fun SettingsScreen(
    repo: WbRepository, session: Session,
    onOpenConfig: () -> Unit, onOpenUsage: () -> Unit, onOpenPackages: () -> Unit,
    onOpenProbes: () -> Unit, onOpenVouchers: () -> Unit, onDisconnect: () -> Unit,
    onThemeChange: (String) -> Unit, onRefreshChange: (Int) -> Unit,
)
@Composable fun ConfigScreen(repo: WbRepository, onBack: () -> Unit)
@Composable fun UsageScreen(repo: WbRepository, onBack: () -> Unit)
@Composable fun PackagesScreen(repo: WbRepository, onBack: () -> Unit)
@Composable fun ProbesScreen(repo: WbRepository, onBack: () -> Unit)
@Composable fun VouchersScreen(repo: WbRepository, onBack: () -> Unit)
```
屏幕内不要自建 Scaffold；内容根用 `Column(Modifier.fillMaxSize())`，第一项 `WbTopBar(...)`，
其余用 `LazyColumn` / `Column(Modifier.verticalScroll(...))`，底部预留 24dp。

## 7. API 约定（服务端）
- 鉴权 `Authorization: Bearer <api_key>`；api_key 为空时服务端免鉴权。
- 响应三种信封：`{"ok":true,...}` / 裸对象（overview、logs、request_metrics、request_logs、
  usage、packages、school/vouchers、tasks/queue、model_probes）/ 错误 `{"ok":false,"error":"..."}`。
  **以 HTTP 状态码为准**，`ok` 字段可能不存在。
- 零时间 `"0001-01-01T00:00:00Z"` 视为 null；数组字段可能为 null，统一 `?: emptyList()`。
- `credits_total == -1` 表示企业版不限额度；`login/poll` 的 `credits == -1` 表示余额查询失败。
- 轮询：总览/账号 5s；队列 3s（仅 running 时）；登录 3s；日志 5s；模型/套餐/用量手动刷新。
- 账号动作：`POST /panel/api/accounts/{uid}/{revive|disable|pause|resume|checkin|balance|remove}`。
- 任务：`GET /panel/api/accounts/{uid}/tasks`；`POST .../tasks/accept {task_codes:[]}`；
  `POST .../tasks/accept_all`；`POST .../tasks/claim {task_code}`；`POST .../tasks/auto {task_code}`；
  `POST .../tasks/auto_all`；`POST /panel/api/tasks/scan_all`；`POST /panel/api/tasks/run_queue
  {concurrency:2}`；`GET /panel/api/tasks/queue`。
- 批量：`POST /panel/api/{checkin_all|travel_all|activity_all|keepalive_all|balance_all}`（前 4 个
  异步返回 `{ok,started}`，balance_all 同步返回 `{ok,accounts}`）。
- 登录：`POST /panel/api/login/start {realm}` -> `{ok,url,state,realm}`；
  `GET /panel/api/login/poll?state=` -> `{done,uid,nickname,realm,credits,...}`；404 = 过期。
- 配置：`GET /panel/api/config` -> `{ok,path,config}`；`POST` 为**深合并补丁**，返回
  `{ok,restart_required:[...]}`。配置含明文密钥，UI 必须打码。
- 用量：`GET /panel/api/usage?hours=72`（0=全部）；套餐：`GET /panel/api/packages`；
  探测：`GET /panel/api/model_probes`；券码：`GET /panel/api/school/vouchers`。
