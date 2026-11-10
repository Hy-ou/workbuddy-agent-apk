# 开发记录（CHANGELOG）

> 版本号对应 `android/app/build.gradle.kts` 的 `versionName`；日期为开发/验证时间（Asia/Shanghai）。
> 详细问题排查过程见 [docs/troubleshooting.md](docs/troubleshooting.md)。

---

## 3.13.2 — 2026-11-10

### 修复：虚拟屏「启动应用不生效」

- **根因 1**：真机上已有同 App 任务时，`am start --display <id>` 只会把旧任务带到前台，
  仍留在 display 0；旧代码只看退出码，误报成功。
- **根因 2**：微信等 App 启动后由自己的进程拉起登录页/主界面，这些内部 Intent 不带
  launchDisplayId，系统把它们放回 display 0，虚拟屏无法拦截。
- **修复**：`VirtualScreen.launch` 先 `am force-stop <pkg>`，再 `am start --display`；
  等待 2 秒后读 `dumpsys activity activities`，按 `Display #N` 段落核对目标包实际落点。
  只有任务在虚拟屏上才返回成功；落在真机/找不到任务时返回明确错误，并在控制台卡片下方
  常驻显示 + Toast。UI 增加「启动会结束真机实例」的说明。
- **实测（同一路径 `POST /vdisplay`）**：
  - `start status` → `state: on, display 12, ok: true`
  - 启动设置 → `ok: true, message: 已在虚拟屏启动 com.android.settings`
  - 启动微信 → `ok: false, error: 系统把 com.tencent.mm 放到了真机（display 0）...`
- 支持副屏的 App（设置、KernelSU、Chrome 等）正常；微信等拒绝副屏的 App 明确报错，不再假成功。
- 版本 **3.13.2 / versionCode 31**；58 tests / lint 0。详见
  [release/RELEASE_NOTES-3.13.2.md](release/RELEASE_NOTES-3.13.2.md)。

---

## 3.13.1 — 2026-11-10

### 修复：虚拟屏在 Android 17 / HyperOS 上无法启动 App

- **根因**：3.13.0 用 App 自建 VirtualDisplay / overlay display。Android 17 的
  `SecurityManagerService.isVirtualDisplayActive` 明确拒绝普通 App 把**其他 App**
  启动到虚拟屏（即使 App 是 display owner）；`screencap -d <逻辑 id>` 也报
  `Display Id 'x' is not valid`（它要 SurfaceFlinger 物理 ID）；HyperOS 的跨应用
  「应用跳转」确认还会因为无法显示在虚拟屏而直接失败。
- **架构重写**：新增 `android/vds-helper`（`com.wb.vds.Server`，打包为
  `assets/vds.dex`）。App 通过 Shizuku 以 uid 2000/root 执行
  `app_process -Djava.class.path=... com.wb.vds.Server W H DPI cache/vds`：
  - 服务创建 `VirtualDisplay(PUBLIC | OWN_CONTENT_ONLY)` 并成为 display owner；
  - 服务写 `id/pid/meta/frame.png`（约 3fps）到 App 缓存；
  - App 启动 App 用**同一 uid** 的 `am start --display <id> -n '<pkg>/<act>'`；
  - 输入仍用 `input -d <id>`；
  - 不再改 `overlay_display_devices` / `force_desktop_mode`，截图不再用 `screencap`。
- **状态校验**：App 重进控制台时读 `pid` 并 `kill -0`，服务已死则清掉残留 `id/frame`，
  不再出现「服务没了还显示运行中」；旧版残留（无 `pid`）同样清理。
- **依赖**：虚拟屏创建/截图/启动/输入全部依赖 Shizuku（Android 8+）；控制台与工具提示同步更新。
- **验证**：Redmi K90 Ultra（M332BF）/ Android 17 / KernelSU root + Shizuku root：
  720×1280@320 → displayId 9、owner uid 0；KernelSU 在虚拟屏 `visible=true`，
  `frame.png` 98 KB，控制台预览正常；杀服务后重进控制台回到「未开启」。
- 详见 [docs/virtual-display.md](docs/virtual-display.md)。

### 修复：DSH 全屏顶部黑边

- 去掉全屏时 WebView 外层的 `WindowInsets.displayCutout` 内边距，页面顶到 y=0；
  顶部浮动「退出全屏」按钮仍按 `statusBars ∪ displayCutout` 避让挖孔。

### 版本

- **3.13.1 / versionCode 30**；单元测试 58 项通过；`lintDebug` 0 error。
- 新增 `android/vds-helper/src/com/wb/vds/Server.java` 与 `assets/vds.dex`（构建命令见
  [docs/virtual-display.md](docs/virtual-display.md)）。

---

## 3.13.0 — 2026-11-10

### 新增：安卓虚拟屏（Agent 后台操作，不影响真机）

- **能力**：新增 `VirtualScreen`（`vdisplay/VirtualScreen.kt`）。基于系统 overlay display
  （`settings put global overlay_display_devices`，与开发者选项「模拟辅助显示」同一机制），
  通过 Shizuku（uid 2000）创建/销毁一块不对应物理屏幕的显示，并支持：
  截图（`screencap -d`）、点击/滑动/按键/文字（`input -d`）、列出桌面应用并
  `am start --display` 启动到虚拟屏。
- **关闭即还原**：创建前记录用户原本的 `overlay_display_devices` 值，关闭时原样恢复，
  不污染开发者选项；displayId 优先从 `DisplayManager` 读取，ROM 不暴露时用截图反查兜底。
- **控制台 UI**：「设置 → 虚拟屏」新增二级页：开启/关闭、三种预设分辨率、实时画面
  （1.5s 自动刷新，可关）、点画面远程点按（按虚拟屏分辨率换算坐标）、文字/返回/桌面注入、
  应用搜索与一键启动、Shizuku 授权入口。截图落 `cache/vdisplay/latest.png`。
- **DSH 内核**：插件 `@workbuddy/dsh-mobile` 新增 `wb_vdisplay`（status/start/stop/apps/launch）、
  `wb_vscreen`（截图路径）、`wb_vinput`（tap/swipe/key/text）；宿主桥新增 `POST /vdisplay`；
  环境手册新增「虚拟屏」一节（何时用、坐标基准、结束要 stop、单实例应用提醒、不做支付类操作）。
- **原生助手**：`MobileTools` 新增 `vdisplay` / `vlaunch` / `vscreen` / `vtap` / `vswipe` /
  `vtype` / `vkey` 工具与协议说明（不依赖无障碍，只要求 Shizuku）。
- **前置条件**：Android 10+（API 29）且 Shizuku 已授权；未满足时页面与工具都给出可读提示。
- **已知限制**：单实例应用（如微信）启动到虚拟屏后真机窗口会被移走；`FLAG_SECURE` 页面截图黑屏；
  真机熄屏后 overlay 显示可能停止合成；虚拟屏内的操作是真实账号操作，禁止用于支付/转账。
  详见 [docs/virtual-display.md](docs/virtual-display.md)。

### 版本

- `versionCode 29` / `versionName 3.13.0`
- 构建验证：`:app:assembleRelease` / `:app:assembleDebug` 通过；`testDebugUnitTest` 58 项通过；
  `lintDebug` 0 error；插件自检 `node android/dsh/tests/wb-mobile.selftest.mjs` 7 个工具通过。
  已在 Redmi 25019PNF3C（Android 17 / arm64）上安装并启动验证：App 正常进入总览、设置与「虚拟屏」控制台；该机已安装 Shizuku 但本机测试时未启动/授权，虚拟屏的创建/截图/注入未实测。

---

## 3.12.0 — 2026-11-10

### 新增：DSH 控制台全屏沉浸

- 进入「助手 → DSH 内核」默认全屏：隐藏 App 顶栏（标题 / 网关信息 / 日志 / 重启）与底部
  6 Tab 导航，同时隐藏系统状态栏与手势条（上滑可临时唤出），WebView 占满整屏；
  全屏态下按 `WindowInsets.displayCutout` 避让挖孔 / 刘海，无开孔机型仍是纯整屏。
- 顶部居中浮动「退出全屏」胶囊按钮：一点回到 App 界面（顶栏 + 底部导航恢复）；
  退出全屏后 DSH 顶栏新增「全屏使用」按钮可再次进入；系统返回键优先退出全屏，
  不会一按就直接退出 App。
- 进出全屏只改变布局约束，不重建 WebView（页面状态、输入框内容、滚动位置都保留）。
- 全屏状态用 `rememberSaveable` 保存在当前会话；切到其它 Tab 再回来仍是全屏。

### 优化：整体卡顿 / 掉帧

1. **视口修复脚本重写**（最大头）：旧实现挂在 `document.documentElement` 上的
   `childList+subtree` MutationObserver 会在聊天流式输出时被每个 token 触发，随后全量遍历
   所有样式表 + 所有 `[style]` 元素做 `setProperty`，相当于每 350ms 强制一次整页样式重算。
   现在改为 `ResizeObserver` + 高度去重（只在视口真的变化时改一次高度），vh→px 全量换算
   只在首帧做一次，运行时新增的 `<style>` 只补丁该节点。
2. **WebView 渲染**：底色由透明改为不透明（少一层整屏 alpha 混合）、开启
   `offscreenPreRaster`（长会话滚动更稳）、关闭 overscroll 与滚动条；
   页面主题色回调直接修改 WebView 背景与系统栏图标，不再回传 Compose 状态，避免主题切换
   触发整屏重组与 WebView 重新布局。
3. **日志**：release 包的 `console.log` 不再逐条写 logcat（只保留告警 / 错误并 300ms 限流），
   流式输出时渲染线程不再被日志 I/O 拖慢；debug 包保持全量。
4. **WebView 生命周期**：离开 DSH 页 / 内核重启时主动 `stopLoading + removeView + destroy`，
   反复进出不再累积渲染进程与 JS 堆（长时间使用后的整体变慢）。
5. **重组范围**：`agentClient` / `gatewayStatus` 只在原生助手分支订阅，DSH 页不再跟着网关
   状态变化整屏重组；DSH 的进度 / 错误分支单独成层。
6. **轮询**：`Loader` 静默轮询结果与旧值相等时不写状态、`quiet` 刷新不再拨动 `refreshing`
   （总览每 5 秒整表重组 + 下拉刷新指示器闪烁）；轮询绑定生命周期，后台不再打接口，
   回前台立即静默刷新一次。

### 版本

- `versionCode 28` / `versionName 3.12.0`
- 构建验证：`:app:assembleRelease` 通过；`testDebugUnitTest` 58 项全部通过；
  未做真机帧率实测（本机无连接设备），全屏与掉帧相关改动以代码路径与构建验证为准。

---

## 3.11.3 — 2026-11-08

### 修复：锁屏后内置网关「停止工作」（进程还在但不应答 / 过夜后进程消失）

**现象**：锁屏一段时间再回来，通知还在、`libwb2api.so` 也还在，但锁屏期间网关的定时任务
（签到 / 保活 / 余额刷新）一条都没跑，`/healthz` 直接超时；锁屏过夜后更严重——通知消失、
进程没了，只能重开 App。

**根因（四层叠加，按触发顺序）**：

1. **HyperOS 的 GreezeManager 锁屏即冻结整个 UID**（`reason=screen_off`；切后台是 `reason=tobg`）：
   日志里明明打印了 `HasForegroundService`，仍判 `freeze_able` —— 前台服务在这套 ROM 上不是豁免条件。
   冻结按 UID 生效，`libwb2api.so` 与宿主同处 `apps/uid_x/pid_y` cgroup，一起停摆，
   `wb:gateway` wakelock 被系统置为 `DISABLED`。
2. 全程没有 wakelock：前台服务只防回收、不防 CPU 休眠，屏幕一灭 Go 的定时器就停。
3. `foregroundServiceType="dataSync"` + targetSdk 35：Android 15+ 该类型有 6 小时 / 24 小时后台配额，
   跑满后系统回调 `Service.onTimeout()`，默认实现直接抛异常杀进程（锁屏过夜必现）。
4. 没有恢复路径：`onDestroy` 无条件 `stop()` 杀网关，`START_STICKY` 重建服务后不重拉网关，
   也没有看门狗 / 回前台自检 —— 一次中断就永久停摆。

**修复**：

- 前台服务类型 `dataSync` → `specialUse`（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE=local_api_gateway`，无配额），
  新增 `WAKE_LOCK` / `FOREGROUND_SERVICE_SPECIAL_USE` 权限。
- `GatewayService`：服务存活期持 `PARTIAL_WAKE_LOCK`（看门狗 30s 幂等续持）、重写 `onTimeout()`、
  30s 看门狗 `ensureRunning()`、`onTaskRemoved` 不跟着停、`onDestroy` 只在用户主动停止时才杀网关。
- `LocalGatewayManager`：新增 `ensureRunning()`（幂等重拉）、`stopByUser()` / `consumeStopRequest()`
  （区分用户停止与系统回收）、`watchExit()`（进程退出立刻置 Failed，杜绝「假活」）。
- `AppViewModel` + `MainActivity`：`ON_RESUME` 自检，解锁回前台自动恢复并重挂顺延端口。
- 设置页「运行体检」新增 **锁屏冻结（HyperOS）** 引导，一键跳「省电策略」/「自启动管理」。

**验证**（Redmi M332BF / Android 17）：`types=0x40000000`（specialUse）、`PARTIAL_WAKE_LOCK 'wb:gateway'`
已持有；关屏 8 分钟复现 `cgroup.freeze=1` + `/healthz` 超时，解锁后 `freeze=0`、`/healthz` 立即 200。
HyperOS 仍需把省电策略设为「无限制」，完整复现与验证步骤见 `docs/troubleshooting.md` 第 12 节。

---

## 3.11.2 — 2026-11-06

### 修复：面板导出的账号 JSON 在手机端导入失败（格式不兼容）

**现象**：Web 面板「导出」/「批量导出」得到的 JSON，在 App「添加账号 → 导入 JSON」中选择后报错：

- 单账号导出（对象）→ `导入失败：invalid json: json: cannot unmarshal object into Go value of type []panel.cockpitAccount`
- 批量导出（数组）→ 显示「成功 0 个，跳过 N 个」，错误 `missing required fields (id=)`

**根因**：两端格式不一致。面板导出的是与 `auths/workbuddy-*.json` 同构的嵌套形：

```json
{"auth":{"accessToken":"…","refreshToken":"…","expiresAt":1753600000,"domain":"…","realm":"cn"},
 "account":{"uid":"…","enterpriseId":"","nickname":"…"},"device_token":"…"}
```

而网关 `POST /panel/api/import/cockpit` 只按 cockpit tools 的扁平数组解析：
`[{uid,access_token,refresh_token,expires_at(毫秒),...}]`。嵌套对象被 `json.Unmarshal` 直接拒绝，
嵌套数组则被扁平结构忽略成空字段，表现为 400 或全量跳过。

**修复**：

- 网关 `internal/panel/import.go` 新增 `parseImportAccounts`：兼容 cockpit 扁平数组、面板单账号对象、
  面板批量数组；嵌套形统一折算成 cockpit 结构（`expiresAt` 秒→毫秒，`realm` / `enterpriseId` /
  `device_token` 保留）。
- 导入循环改为显式 `realm` 优先，`enterprise_id` / `device_token` 一并落盘。
- 重新交叉编译 `libwb2api.so`（arm64-v8a / x86_64），App 版本 3.11.2 / versionCode 26。
- App 导入页提示改为「支持 cockpit tools 账号数组，也支持本面板导出的凭证 JSON（单个或批量）」。

**验证**：单账号/批量导出文件在网关侧导入 `imported=1 / 2, skipped=0`；原 cockpit 扁平数组仍兼容；
`go test ./...` 全绿；Android `testDebugUnitTest` 通过。

---

## 3.11.1 — 2026-11-05

### 修复：DSH 对话被上游内容防火墙拦截（400 / code 11-128，日志出现 content blocked WARN）

**现象**：手机端「日志 → 运行日志」在对话时出现

```text
WARN: [server] content blocked: outbound body dumped to data/content-blocks/content-block-20261105-154954-….json
(101602 bytes, realm=cn model=deepseek-v4.1-flash upstream_request_id=…)
content-blocked (likely fingerprint false positive) -> degraded prompt retry
```

客户端因网关在同请求内换降级提示词重试而无感，但每次白撞一次上游往返，且降级期间 system 提示词被替换。

**排查**：从手机 `/data/data/com.workbuddy2api.panel/files/gateway/data/content-blocks/` 取出三份落盘体
（11-05 05:27 / 15:36 / 15:49，57–130 KB），用面板仓库 `cmd/fingerprint-probe -body-file … -bisect`
直连上游二分，33 次探针收敛到 1 条 407 B 最小触发子串（DSH 的 @ 路径引用提示词段）：

```text
Tokens prefixed with @ are paths the user explicitly referenced. Relative paths resolve from the
workspace root; absolute paths identify files or directories on the host. A trailing slash marks a
directory: list it when its contents matter. Anything else is a file: use the read tool when its
contents are needed, and do not claim to have inspected it before reading. @"..." quotes a path
containing spaces.
```

上游按整段逐字匹配；三份落盘体都含该段，因此每次带 @ 引用说明的请求都会撞。

**修复**：

- 网关 `internal/upstream/sanitize.go`：新增不区分大小写正则改写 `Relative paths resolve from the
  workspace root` → `Relative paths resolve against the workspace root`（只改一个词，语义不变）；
  `sanitizeFeatures` 增加 `Tokens prefixed with @` 预检标记。
- `internal/upstream/sanitize_test.go`：新增 `TestHarnessPathRefRewritten` 回归用例（改写命中、
  语义保留、段尾逐字不变）。
- 重新交叉编译 `libwb2api.so`（arm64-v8a / x86_64），App 版本 3.11.1 / versionCode 25。

**验证**：同一落盘体修复前 `status=400 code=11-128` → 修复后 `status=200`；`go test
./internal/upstream -count=1` 全绿；真机安装后 DSH 对话日志不再出现 `content blocked`。

---

## 3.11.0 — 2026-11-04

### 新增：账号页支持通过 JSON 文件批量添加账号（对齐 Web 端）

**背景**：Web 面板的「添加账号」弹窗有「浏览器登录 / 导入 JSON」两个页签，可上传 cockpit tools
导出的账号数组一次补号；手机端此前只有 OAuth 授权一条路径，换机或批量补号时只能逐个登录。

**实现**（客户端功能，网关侧 `POST /panel/api/import/cockpit` 已有，无需改 Go 代码）：

- **数据层**：`WbApi` 新增 `postFile()`（`multipart/form-data`，字段名 `file`，默认 5 分钟超时——
  服务端导入时会逐账号签到/查额度）；`WbRepository` 新增 `importCockpit(fileName, bytes)`，
  解析 `{ok,total,imported,skipped,errors}`；演示仓库返回模拟结果。
- **界面**：账号页 FAB「添加账号」弹层新增「浏览器登录 / 导入 JSON」页签。切到「导入 JSON」后
  用系统文件选择器（SAF，`OpenDocument`，支持 `application/json` / `octet-stream` / `text`）选文件，
  选中即上传（与 Web 端一致），就地显示「成功 N 个 / 跳过 N 个 / 共 N 个」及前 3 条错误，
  成功后自动刷新账号列表。
- **边界**：单文件上限 32 MiB（与服务端 `ParseMultipartForm` 对齐）；空文件、读不到文件、
  账号数组格式错误、旧版网关 404（提示升级）均有中文错误提示；导入中禁用按钮并显示进度。

**验证**：`WbApiTest` 新增 `importCockpitUploadsMultipartJsonFile`（断言路径、Bearer、multipart
文件名/内容）与 `importCockpitSurfacesServerError`（400 invalid json 透传）；`compileDebugKotlin`、
`lintDebug`、`testDebugUnitTest`、`assembleRelease` / `assembleDebug` 均通过。内置 `libwb2api.so`
已含该路由（字节校验 `POST /panel/api/import/cockpit`）。产物 `release/WorkBuddy-3.11.0.apk`
（116.7 MB，SHA-256 `9ac51996…204edb10`）与 debug 包已生成，详见 `release/SHA256SUMS`。

---
## 3.10.2 — 2026-10-27

### 修复：每天首次请求被上游内容防火墙拦下（400 / code 11-128），随后一整天降级

**现象**：网关日志出现

```text
WARN: [upstream] chat_stream acct=…: upstream 400 content_blocked
body={"code":11-128,"msg":"Illegal API invocation from an unapproved channel",…}
content-blocked (likely fingerprint false positive) -> degraded prompt retry
```

客户端无感（同请求内换降级提示词重试后 200），但代价是：① 每次白撞一次上游往返；
② 降级状态**持续到次日 00:00**，期间**所有** `passthrough` 请求的 system 提示词都被替换成
3 句中性文本——内置 DSH 的工具约定/项目规范整套丢失；③ 手机端网关通常不重启，等于一次误报
锁死一整天，且没有任何日志或界面提示。

**根因**：上游内容审核是**逐字精确匹配**（非语义），而内置 DSH 的**一手 system prompt** 里有两处
字符串命中它的黑名单。用新加的二分探针（见下）在同账号上 100% 复现并收敛到：

| # | 触发子串 | 长度 | 来源（DSH 侧） | 边界 |
|---|---|---|---|---|
| 1 | `You are an AI agent running on DeepSeek Harness` | 47 B | `dsh-system-prompt`（`harness:identity`, order −1000） | 上游**大小写不敏感**；"You are an AI agent powered by" 与 "DeepSeek Harness" 必须同时在场 |
| 2 | `You are interacting with the user via the DeepSeek Harness Web GUI … without naming another target, that means this GUI.` | 221 B | `dsh-harness-dev-context`（`app:web-surface`, order 10100） | 整串逐字匹配，**结尾 `GUI.` 必需**（220 B 缺句号→200，补上句号→400；两句各自单独 200） |

决定性验证：真实渲染的 system prompt（7656 B）→ 400；**只去掉 #1 仍 400**；两处都去掉（6598 B）→ 200。
两条都在 DSH 自己的提示词里，与用户内容、工具声明无关。

**修复**：

- **网关词表**（`internal/upstream/sanitize.go`）：新增 `sanitizeHarnessRewrites`，对本族指纹走 `(?i)`
  正则而非字面替换（上游不区分大小写，字面 `ReplaceAll` 在小写混杂形态下会漏），每条只改一个词、
  语义不变：`powered by → running on`、`through → via`、`they mean → that means`；harness 名字保留。
  预检标记用 `"DeepSeek Harness"` / `"deepseek harness"`，只是提到产品名的正文逐字节不动（有测试守护）。
- **取证落盘**（新增 `internal/server/contentblock_dump.go`）：`ErrContentBlocked` 时把出站请求体与
  上游响应体写进 `<state 同目录>/content-blocks/`（单份 8 MiB 上限、保留 20 份、按 rune 边界截断、
  原子写、失败只 WARN），供下一枚未知指纹离线复盘；配置键
  `logging.content_block_dump_{enabled,max_mb,keep}`。App 侧在 `LocalGatewayManager` 生成的 config 里
  **显式开启**（手机上既没有命令行也没有 stdout，这份落盘是唯一证据）。
- **新工具** `cmd/fingerprint-probe`：`-list` / `-system-file` / `-body-file`（吃落盘体）/ `-bisect`
  （块 → 行 → 句递归收缩到最小触发子串）/ `-sanitize=false`；直连上游、不经网关（不污染降级状态）。
  本次两枚指纹即由它定位。
- **构建脚本**（`android/build_gateway.ps1`）：本仓库不含 Go 源码，旧脚本从仓库根找 `cmd/server` 必失败，
  新增 `-SourceRoot` 与工作区同级自动探测（找不到 fail fast 并给出可直接复制的命令）；`go build` 前
  `Push-Location` 到源码根（`go build` 按**当前目录**定位 module，传绝对包路径也会报
  `cannot find main module`）；日志打印 Go 源码 rev，`.so` 因此可溯源到面板仓库提交。

**验证**：

- 真实渲染 system prompt 打上游：修复前 **400/11-128** → 修复后 **200**；`sanitize=false` 对照仍 400
  （排除上游抖动）；另 4 份会话变体（7660/7650/7654/6598 B）全部 200。
- 内嵌 `libwb2api.so`（arm64-v8a / x86_64）字节校验：两枚指纹的改写串、取证落盘串均存在，旧包不存在。
- 面板仓库 `go test ./...` 全绿；新增回归用例 `TestHarnessIdentityRewritten` /
  `…LowercaseRewritten` / `…WebSurfaceRewritten` / `…MentionOnlyUntouched` 与 4 条落盘用例。

**影响面**：只改写命中这两处指纹的文本（`system`/`developer`/`user`/`assistant`/`tool_calls`/`reasoning_content` 全覆盖）；
其余请求字节不变。`custom` 模式本就替换客户端 system，不受影响。

---
## 3.10.1 — 2026-10-25

### 修复：对话页「添加文件」点击后不弹系统选择器

**现象**：App 内 DSH 控制台对话页，点输入框左下角「+」→ 菜单里的「文件」后，系统文件选择器
完全不出现——页面没有报错、没有 toast，也没有任何请求。

**链路**（先厘清，否则容易改错地方）：DSH 网页里**没有独立的附件按钮**，只有两条入口——
「+」菜单的 `file` 动作，以及把文件拖进页面。点菜单项时前端走
`ctx.filePicker.open()` → `input[type=file].click()`（`@deepseek-ai/dsh-client-ui-conversation`
0.2.1-rc.2 的 `bindFilePicker`），之后是宿主的 `WebChromeClient.onShowFileChooser` → 系统选择器
Activity。前两步在前端，后两步在宿主。

**根因**：宿主的 `onShowFileChooser` 只有一条路径 `params.createIntent()`，任何一步不成立
（`params` 为空、Intent 不可启动、定制 ROM 裁掉了对应 DocumentsUI、`launch()` 抛异常）都走
`catch { return false }`。WebView 收到 `false` 就**静默不弹窗**，连日志都没有，于是前端看到的
就是「点了没反应」。附带两个同链路的隐藏缺陷：结果只读 `data.data`（多选时系统只填
`clipData`，等于「选多个 = 什么都没选」）；上一个 callback 没释放时会让后续选择器永久不弹。

**修复**（`ui/dsh/DshScreen.kt`）：

- **兜底链**：`params.createIntent()` → `ACTION_OPEN_DOCUMENT` → `ACTION_GET_CONTENT`，带
  `EXTRA_ALLOW_MULTIPLE` / `EXTRA_MIME_TYPES` / `FLAG_GRANT_READ_URI_PERMISSION`，逐条 try，
  直到某一条真正 launch 成功；全部失败才把 `null` 还给 WebView。
- **结果收集**：新增 `collectPickedUris()`，先读 `clipData` 全部项再读 `data.data` 去重，多选可用。
- **不再挂起 callback**：新请求先释放上一个，并用 `DisposableEffect` 在页面销毁时归还。
- **`FILE_PROBE_JS`**：把 React 用 `hidden` 属性渲染的 file input 从 `display:none` 改成 1px
  不可见块（个别 ROM 的 Blink 会忽略对 `display:none` 输入框的程序化 `click()`），并在每次点击
  时输出 `[wb] file input click ...`。
- **全链日志**：`WbDshFile`（宿主回调入参、每条 Intent 的启动结果、选择结果条数）、
  `WbDshWeb`（页面 console，含上面的探针）。

**验证**：真机 Xiaomi M332BF（Android 15 / WebView Chromium 149）实测通过——
「+」→「文件」弹出系统选择器，选中文件后输入框上方出现附件卡片（名称 / TXT / 20B），可正常发送；
logcat 链路 `[wb] file input click` → `onShowFileChooser mode=...` → `launch ... -> true` →
`picker result code=-1 uris=1` 全部打通。

另外用 WebView 远程调试（debug 包 CDP `DOM.setFileInputFiles`）单独验证了前端入队：
不走原生选择器直接给 input 塞文件时，卡片同样出现，说明「选完没反应」只可能出在原生把 URI 交回
WebView 这一段，前端入队与后台上传本身没有问题。

排查时按 logcat 组合定位（三种根因）：有 `[wb] file input click` 无 `onShowFileChooser` →
WebView 没回调；有 `onShowFileChooser` + 全部 `launch ... -> false` → 设备没有可用选择器
Activity；有 `picker result ... uris=N` → 宿主侧已通。

- 版本 3.10.1 / versionCode 22。

---

## 3.10.0 — 2026-10-21

### 新增：内置 DSH 插件 `@workbuddy/dsh-mobile`（Android 执行通道 + 环境手册）

**背景**：真机上 Agent 频繁调用 bash 等工具报错。根因有三条，都在宿主侧：

1. `@deepseek-ai/dsh-bash-local` 执行的是 `spawn("bash", "-c", …)`，而 **Android 没有 bash**
   （只有 `/system/bin/sh` = mksh + toybox）；`bash-sandbox`/`pwsh-sandbox` 在托管补丁里本来就禁用，
   于是「唯一的 shell 工具」正好是跑不起来的那个。
2. 即使有二进制也 exec 不了：`untrusted_app` 不能 exec `app_data_file`，内核树又落在 /sdcard（noexec）。
3. 内核的 system prompt 里没有任何「这台手机有什么」的事实（文件权限、PATH、工具链、Shizuku、npm），
   模型只能按 Linux 桌面习惯猜，猜错即报错，还会原样重试。

**选择**：写成插件而不是 Agent 预设 —— 预设只能在已有插件里挑组合，既造不出 shell 通道，
也无法把随环境变化的运行时事实写进提示词（对比见 docs/mobile-agent-plugin.md）。

- **插件本体** `android/dsh/assets/dsh-plugins/wb-mobile/`（零依赖 ESM，只 import `node:`）：
  - `wb_sh`：`/system/bin/sh -c`，返回 exitCode/stdout/stderr/timedOut，48 KB 输出上限 + 溢出 spill；
  - `wb_env`：体检（可用/不可用命令、PATH、工具链与 npm CLI 清单、共享存储可写性、桥/Shizuku 状态）；
  - `wb_shizuku`：经宿主回环桥以 uid 2000 执行（pm/input/screencap/settings/dumpsys）；
  - `wb_notify`：系统通知；
  - 系统提示词段 `workbuddy:android-environment`（order 1050）：文件权限与路径约定、POSIX-only 语法、
    toybox 与 GNU 差异、npm/pnpm 位置、工具链安装位置、Shizuku 边界、「报错先读 stderr、禁止原样重试」。
- **宿主安装** `dsh/MobilePlugin.kt`：每次启动内核前从 assets 复制到
  `dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile` 并登记到 `InstalledPlugins`，
  由 `syncManagedPatch` 写进 `cordis.patch.yml` 的 insert 行（id `wb-mobile`）。
  必须放 profile：DSH loader 用 profile 作 baseUrl 解析包名，放内核树会 ERR_MODULE_NOT_FOUND。
- **bash 兜底**：`home/bin` 增加 `bash`/`sh` 链接 → `libwbnode_shim.so`，`shim.c` 把它们落到
  `/system/bin/sh`（含 `#!/bin/bash`、`#!/bin/sh` shebang 解析），官方 bash 工具因此也能跑；
  顺带删掉 shebang 分支里那条会以无参方式 exec 解释器的兜底调用。
- **宿主桥扩展** `dsh/LocalBridge.kt`：新增 `POST /shell`（Shizuku，未授权返回 409 + 原因）；
  `POST /health` 现在返回 `shizuku.available/granted`，供 `wb_env` 体检。
- **修复宿主桥中文正文卡死**（真机复现）：请求体原先按「字符」读，而 `Content-Length` 是字节数，
  UTF-8 中文正文永远读不满 → 连接挂到 5 s 超时、客户端只看到 fetch failed（纯 ASCII 正常）。
  改为按字节读头部与正文（并给 body 加 1 MB 上限），通知/命令正文都可以是中文。
- **构建**：`app/build.gradle.kts` 增加 `sourceSets.main.assets.srcDir("../dsh/assets")`，插件源码单点维护。
- **自检** `android/dsh/tests/wb-mobile.selftest.mjs`：不需要设备，校验 4 个工具的 JSON Schema
  （DSH 允许的子集）、渲染与失败路径；已用内核自带的 `assertSupportedJsonSchema` 交叉验证通过。
- **真机坑 1：启动器必须重新编译**。`bash`/`sh` 回退写在 `shim.c` 里，若沿用旧
  `libwbnode_shim.so`（例如从上一版 APK 里取 .so），`bash` 仍会报 `wbexec: target not found for bash`（exit 127）。
  改动 `shim.c` 后必须跑 `pwsh android/dsh/build-shim.ps1`（NDK clang，两个 ABI）。
  实测：新启动器在应用 uid 下 `bash -c 'echo hi'` → `argv0=/system/bin/sh`、exit 0。
- **真机坑 2：PTC 模式里不能用 `execSync`**。内核的 node 是 Termux 构建，
  二进制里硬编码了默认 shell `/data/data/com.termux/files/usr/bin/sh`（`grep -a -c termux` = 22），
  该路径在 Android 上不存在，`run_code` 里任何 `child_process` 调用都是
  `spawnSync ... ENOENT`。环境手册已按 PTC 重写：先声明「只能直接调 run_code，其它工具用
  `await tools.xxx(...)`」，并给出 `tools.wb_env`/`tools.wb_sh` 的示例。
- 版本 3.10.0 / versionCode 21。

---

## 3.9.3 — 2026-10-21

### 修复

- Web 端 `spawn pnpm EACCES` 真正根因：内核 PATH 缺 `home/bin`，`pnpm` 落到 /sdcard 上
  noexec 的 Termux 版；改为 PATH 首位固定 `home/bin`。
- 验证：`which pnpm` → home/bin/pnpm；`pnpm view dshmarket …` 正常返回插件信息。
- 版本 3.9.3 / versionCode 20。

---
## 3.9.2 — 2026-10-21

### 修复

- 设置页卸载失败：`pnpm remove` 不支持 `--registry`（Unknown option），改为仅安装时传。
- DSH 启动 `Cannot find package`：卸载后 patch 残留 insert；改为内核启动时以磁盘为准
  丢弃失效登记，卸载时无条件清登记并重写 patch。
- 验证：卸载 dsh-whale-widget、安装 dshmarket 均成功；重启内核无 warning。
- 版本 3.9.2 / versionCode 19。

---
## 3.9.1 — 2026-10-21

### 修复

- DSH Web 端「添加插件」`spawn pnpm EACCES`：
  - 启动器 pnpm 分支补上 `pnpm10/bin/pnpm.cjs`（3.9.0 的路径替换未生效）；
  - 启动时删除内核 `bin/` 里 noexec 的 Termux `pnpm/npm/npx`，避免 PATH 回退命中。
- 验证：应用进程内 `spawnSync("pnpm", ["view","dshmarket",…])` → status 0，
  返回 `{"name":"dshmarket","version":"1.66.45"}`；pnpm 10.34.7。
- 版本 3.9.1 / versionCode 18。

---
## 3.9.0 — 2026-10-21

### 修复

- **工具链无法执行**：应用进程不能 exec 私有目录二进制（SELinux）；改为
  `home/bin/*` → jniLibs 启动器 → `/system/bin/linker64 <目标>` 加载；脚本解析 shebang 映射前缀。
- **换设备插件装不上**：pnpm 内置进 payload（pnpm10，纯 JS），不再依赖首启联网安装；
  升级时 `ensureExtraPayload()` 增量补解压，不覆盖用户数据。
- **DSH Web 端安装插件报 UNEXPECTED_STORE**：内核每次启动自动对齐
  `profiles/web/node_modules/.modules.yaml` 的 storeDir。
- 工具链新增 `verifyCmd`，装完立即自检并回显。

### 验证

- 应用进程内：git 2.56.0 / sqlite3 3.54.1 / Python 3.14.8（print(1+1)→2，sys.prefix 正确）。
- 社区插件 dsh-cost-meter、dsh-free-search 安装成功并写入 patch。
- 16 KB 对齐 16/16 通过；release 116.7 MB；versionCode 17。

---
## 3.8.2 — 2026-10-21

### 修复

- 启动警告 `1 entry did not activate`：`cordis.patch.yml` 残留 wb-notify；改为按 id 清理
  （`InstalledPlugins.removeById`）。
- 社区插件安装失败（四处根因）：
  1. 改到 `dshhome/profiles/web` 安装（内核树在 /sdcard 上被 FUSE 拒绝，且 DSH 只认 profile）；
  2. pnpm 固定 10.x + `--omit=optional --ignore-scripts`（避开原生 @pnpm/exe 的 EACCES）；
  3. 安装前对齐 `.modules.yaml` 的 storeDir 到 `store/v10`，并去掉 XDG_DATA_HOME；
  4. `pnpm add/remove` 加 `--workspace-root`。
- 真机验证：应用内安装 dsh-cost-meter 成功，patch 自动登记，重启后日志显示插件已加载、
  无 warning。
- 版本 3.8.2 / versionCode 16。

---
## 3.8.1 — 2026-10-21

### 修复：DSH 启动被 UI 协程取消（The coroutine scope left the composition）

- DshManager 增加自有 CoroutineScope + startAsync/restartAsync/startSafeModeAsync；
- doStart 包 NonCancellable，CancellationException 直接抛出不再记为失败；
- UI 全部改为调用 async 门面。
- 真机验证：启动后 3 秒切走标签页，内核仍正常启动。
- 版本 3.8.1 / versionCode 15。

---
## 3.8.0 — 2026-10-21

### 新增：扩展中心（Termux 工具链 19 项）

- `dsh/TermuxRepo.kt`：apt Packages 索引（4 镜像 + apt UA）、Depends 闭包、deb 下载（重试 + SHA-256）。
- `dsh/DebExtractor.kt`：ar → data.tar.xz → tar 解包，剥离 Termux 前缀，保留符号链接/可执行位。
- `dsh/Toolchain.kt`：19 项目录、patchelf 重写 RUNPATH、文本前缀替换、安装标记。
- patchelf 用 NDK 交叉编译为 `libwbpatchelf.so`（16 KB 对齐），构建脚本入库。
- 内核环境注入 PREFIX/GIT_EXEC_PATH/GIT_TEMPLATE_DIR/GIT_SSL_CAINFO/CURL_CA_BUNDLE/PYTHONHOME。
- 设置 → 扩展能力 → 系统工具链（19 项）逐个安装/卸载，带实时进度。

### 验证

- Git：14 包，212 ELF / 59 文本重定位 → `git version 2.56.0`，init/commit 正常。
- SQLite：5 包，220 ELF / 6 文本 → `sqlite3 3.54.1`，`select 1+1` → 2。
- 版本 3.8.0 / versionCode 14；release 112.4 MB；16 KB 对齐自查通过（16 个库）。

---
## 3.7.1 — 2026-10-20

### 修复：16 KB 页对齐告警

- `libwbnode_shim.so` 改用 `-Wl,-z,max-page-size=16384` 编译（原为 4 KB 对齐）。
- APK 内 15 个 arm64 库全部核查为 16 KB 对齐。
- 新增 `android/dsh/check-16kb.ps1` 校验脚本（CI 可用，失败返回 1）。
- 版本 3.7.1 / versionCode 13。

---
## 3.7.0 — 2026-10-20

### 修复：DSH 内安装社区插件报错（`spawn pnpm EACCES`）

- 根因：DSH 插件安装 `spawn("pnpm")`，Android 10+ 禁止执行应用数据目录文件；且 payload 自带 pnpm
  硬编码 Termux 前缀 `/data/data/com.termux`。
- 修复：新增 NDK 编译的 ELF 启动器 `libwbnode_shim.so` 放入 jniLibs（nativeLibraryDir 允许 exec），
  按 `argv[0]` 分发 pnpm.mjs / npm-cli.js / node；`home/bin` 建 `pnpm`/`npm`/`node` 符号链接并置 PATH 首位，
  每次启动重建；pnpm 运行时首次启动由内置 npm 现装到 `home/pnpmjs`。
- 内核环境新增 `WB_NODE`、`WB_DSH_HOME`。

### 验证（真机）

- `pnpm --version` → 12.14.0，`npm --version` → 11.20.0。
- `dshhome/profiles/web` 内 `pnpm add dsh-cost-meter` 成功（1.4s），验证后已卸载。
- 版本 3.7.0 / versionCode 12；release 111.1 MB。

### 未交付

- 19 项 Termux 系统工具链：需 bundled patchelf + 依赖闭包解析 + 前缀重写，路线写入 README 与发布说明。

---
## 3.6.1 — 2026-10-20

### 修复

- DSH 侧通知从「插件 insert」改为「node 脚本 + `WB_NOTIFY`/`WB_BRIDGE_TOKEN` 环境变量」：
  上游解析器只认自带作用域的插件包，第三方包 insert 会报 `Cannot find package`。
- 真机验证：`POST http://127.0.0.1:7865/notify` 返回 `{"ok":true}`，
  通知栏出现 `wb_agent_notify` 渠道通知。
- 版本 3.6.1 / versionCode 11。

---
## 3.6.0 — 2026-10-20

### 新增

- **内置 npm**：payload 增加 `npm/`（9.1 MB 未压缩，APK +3.1 MB），解压到 `dsh-home/npm`。
- **社区插件一键安装**（`dsh/PluginInstaller.kt`）：`node npm-cli.js install <pkg>
  --prefix dshroot/lib/node_modules/@deepseek-ai/dsh`，装完自动 insert 到 `cordis.patch.yml`；
  支持卸载；UI 显示进度与失败原因。
- **DSH 通知插件**（`dsh/NotifyPlugin.kt`）：`@workbuddy/dsh-tool-notify` 注册 `notify` 工具，
  经宿主回环桥（`dsh/LocalBridge.kt`，127.0.0.1:7865 + `x-wb-token`）发系统通知；
  插件位置与 bash-local 同级（`@deepseek-ai/dsh/node_modules/`）。
- **扩展中心（CLI 工具）**：typescript/tsx/prettier/eslint/http-server/nodemon 一键装到
  `home/tools`，`.bin` 加入内核 PATH。
- 版本 3.6.0 / versionCode 10。

### 验证

- `testDebugUnitTest` + `lintDebug` 通过；真机确认 npm 已解压、notify 插件文件写入、
  `cordis.patch.yml` 含 `wb-notify` insert；release APK 109.9 MB。

---
## 3.5.0 — 2026-10-20

### 新增：通知、Shizuku 特权、扩展能力面板（参考 deepseek-harness-android-app 与 DSH Mobile）

- **AI 通知**：`util/NotificationCenter.kt` + 原生助手 `notify` 工具；只依赖通知权限。
- **Shizuku 免 root 特权**：`util/ShizukuShell.kt`（可用性/授权/命令执行/截屏），
  助手 `shell` 工具可 `pm install`、`input tap`、`screencap`、`settings put`；
  未安装 Shizuku 时优雅降级。Manifest 增加权限与 `ShizukuProvider`。
- **扩展能力面板**：通知/Shizuku 状态、工具链状态（node/npm/git/python）、
  社区插件精选清单（12 个，复制包名到 DSH 插件面板安装）。
- 依赖：`dev.rikka.shizuku:api:13.1.5`、`provider:13.1.5`（`newProcess` 反射调用）。

### 边界说明（未包含）

- 参考项目的「扩展中心」19 项工具链安装（Python/Go/Rust/… + Termux 镜像 + 依赖闭包 + 原子发布）
  与「社区插件一键安装」需要内核内置 npm/pnpm/git（当前精简包已剔除，约 +120 MB），
  以及包管理器级实现，属于后续独立迭代；本期只提供清单与状态，不提供静默安装。
- DSH 侧的 `notify` 工具需要额外插件包（本期只在原生助手提供）。

### 验证

- `testDebugUnitTest` + `lintDebug` 通过；release APK 106.8 MB；真机安装成功。

---
## 3.4.0 — 2026-10-20

### 新增：P2 宿主能力

- **插件开关**（`dsh/DshPlugins.kt`）：扫描 `@deepseek-ai/dsh-*` 包，UI 逐个开关；
  `cordis.patch.yml` 由 App 每次启动重写（`WorkBuddy-managed-patch: v3`），内置策略仍不可关。
- **安全模式**（`DshManager.startSafeMode`）：`dshhome/profiles` 旁置 + 从 payload 恢复出厂 profile，
  保留会话/凭证；记录连续失败次数与最后错误；DSH 失败页与设置页双入口。
- **备份/还原**（`dsh/DshBackup.kt`）：SAF 导出/导入 zip（dshhome、助手会话、网关配置、我的补丁），
  导入前自动备份现有数据。
- **我的补丁**（`dsh/UserPatches.kt`）：内核树 sha256 基线 + 改动捕获 + 启动回放 + 一键回出厂。
- 新增 `DshPluginIdTest`。

### 验证

- 真机：patch v3 生成、`.baseline.tsv`（4.8 MB）生成、内核正常启动。
- `testDebugUnitTest` + `lintDebug` 通过；release APK 106.8 MB。

---
## 3.3.0 — 2026-10-20

### 新增：宿主层能力补齐（参考 deepseek-harness-android-app）

- **系统 CA 合成**（`dsh/SystemCaBundle.kt`）：把 `/system/etc/security/cacerts/*` 合并为
  `runtime/etc/cacert-system.pem`（stamp 缓存），`SSL_CERT_FILE` 优先使用，企业代理/自签 CA 可用。
- **WebView 兼容自检**：读取 Chromium 主版本，<80 时在 DSH 页提示；「运行环境」展示版本与 ABI。
- **状态栏跟随页面底色**：`wbshell.onBg` JS 桥 + `MutationObserver`，控制台切换主题时系统栏同步。
- **电池优化白名单**：新增权限与一键跳转，状态在运行体检中展示。
- **更新检查**（`util/UpdateChecker.kt`）：GitHub Release latest 与本地版本比较，提供下载入口。
- **内核树拆分布局**：`dshroot/`、`bin/` 外置到 `/storage/emulated/0/WorkBuddy/dsh`；
  `dshhome/`、`runtime/`、凭证与日志留在内部 `filesDir/dsh-home`（`/sdcard` 不支持 `chmod`，
  实测外置 home 会让 `dsh-credentials-local` 拒绝启动）。旧合并树首启自动拆分迁移。
- **运行体检卡片**：设置页聚合电池白名单 / WebView / ABI / 内核树位置 / 版本更新。
- 修复：迁移前先结束遗留 node（否则其 cwd 指向旧目录导致删不掉旧树）；带 `tab/mode` extra 启动时强制切页。

### 验证

- `testDebugUnitTest`（新增 `EnvAndUpdateTest`）+ `lintDebug` 通过；release APK 106.8 MB。

---
## 3.2.0 — 2026-10-20

### 新增：所有文件访问
- `AndroidManifest.xml` 增加 `MANAGE_EXTERNAL_STORAGE`（`tools:ignore="ScopedStorage"`）、
  `READ_EXTERNAL_STORAGE`（maxSdk 32）、`WRITE_EXTERNAL_STORAGE`（maxSdk 29），
  并开启 `android:requestLegacyExternalStorage="true"`。
- 新增 `dsh/StorageAccess.kt`：
  - `hasAllFilesAccess()`：Android 11+ 走 `Environment.isExternalStorageManager()`，更低版本查读写权限；
  - `allFilesAccessIntent()`：`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` → 全局列表页 → 应用详情页三级兜底；
  - `sharedRoot()` / `sharedWorkspaceName()`：统一共享存储约定（`/storage/emulated/0/WorkBuddy`）。
- 新增 `ui/components/StorageAccessCard.kt`：
  - `AllFilesAccessCard()`：设置页卡片，状态徽章 + 说明 + 授权按钮，回到前台自动刷新；
  - `AllFilesAccessBanner()`：DSH 页未授权时的一行提示 + 去授权。
- `SettingsScreen` 增加「文件访问」分区；`DshScreen` 顶部挂载提示条。
- `DshManager.workspaceDir()`：授权后 `DSH_WORKSPACE` 使用 `/storage/emulated/0/WorkBuddy`，
  未授权回退应用专属目录；卡片内提示「重启内核生效」。

### 验证
- 真机（M332BF / Android 15）：未授权 → 提示条可见；`appops allow` 后 → 卡片「已授予」、
  提示条消失、`/storage/emulated/0/WorkBuddy` 由 App 创建并被 DSH 用作工作区。
- `testDebugUnitTest` + `lintDebug` 通过；release APK 106.7 MB。

---

## 3.1.0 — 2026-10-20

### 修复：DSH WebView 按钮点击无效
- 现象：DSH 控制台能渲染，但所有按钮点击无响应。
- 排查（WebView 远程调试 / CDP）：
  - `document.documentElement.clientHeight` = 652、`Page.getLayoutMetrics` 视口正常；
  - 但 `height:100vh` 实测为 **0**（连 `about:blank` 也为 0）→ 该机内核（Chromium 149）初始包含块高度为 0；
  - `html/body/#root` 高度链塌陷为 0，`elementFromPoint(按钮中心)` 返回 `HTML`，按钮不在命中树里。
- 修复：页面加载后注入 `VH_FIX_JS`
  - 把 `html/body/#root` 高度钉成实际像素（`!important`）；
  - 遍历样式表把 `vh/dvh/svh/lvh` 就地换算为 `px`（含 `@media` 内嵌规则）；
  - `MutationObserver` 兜底处理 SPA 后续插入的样式；`onPageFinished` 后 1.2 s 再跑一次。
- 附带修复：
  - `shouldOverrideUrlLoading` 放行 `about/data/blob/javascript`，避免前端按钮被拦截；
  - 实现 `onShowFileChooser`（附件按钮可弹系统选择器）；
  - 增加 `WbDshWeb` 控制台/HTTP 错误/主框架错误日志；debug 包开启 `setWebContentsDebuggingEnabled`。

### 修复：助手页顶部切换条压到状态栏
- 移除 `AssistantMode` 内的 `FilterChips`（`Box` 无状态栏内边距）；
- 新增「设置 → 助手引擎」（`Session.assistantMode`，DataStore 持久化），助手 Tab 按配置直接渲染
  原生助手或 DSH 控制台。

---

## 3.0.0 — 2026-10-20

### 新增：DSH（DeepSeek Harness）内核以独立进程接入
- **运行时落地**：Node 47.4 MB + 10 个依赖库写入 `jniLibs/arm64-v8a`（Android 10+ 仅 `nativeLibraryDir` 可执行）。
- **ELF 改写器**（`dsh/pack_dsh.ps1`）：解析 ELF64 → `PT_DYNAMIC`，就地改写 `DT_NEEDED`、`DT_VERNEED`、`DT_SONAME`：
  `libz.so.1→libz_1.so`、`libcrypto.so.3→libcrypto_3.so`、`libssl.so.3→libssl_3.so`、
  `libicu*.so.78→libicu*_78.so`（均为等长替换，无需搬移段表）。
- **内核资产**：`assets/payload-dsh.zip` = 28,137 个条目 / 62.7 MB（dshroot + dshhome + bin + runtime/etc），
  剔除 python/npm/git/rish 与官方自研插件，`cordis.patch.yml` 换成托管版本（禁用 sandbox、office-to-pdf 等）。
- **进程管理** `DshManager`：首启解压（约 3 秒）、拉起 `dsh web --host 127.0.0.1 --port 8300 --no-open`、
  解析一次性 token URL、健康检查、日志轮转与 tail、PID 文件清理孤儿进程、端口冲突顺延。
- **UI**：`ui/dsh/DshScreen.kt` 提供状态卡（解压/启动/失败）、WebView 控制台、日志弹层、
  网关接入信息（Base URL / API Key 一键复制）。
- 集成入口：助手 Tab 内「原生助手 / DSH 内核」分段切换（3.1.0 移至设置）。

### 修复（同版本迭代中发现）
- token URL 被加载两次导致 401：改为只在 `factory` 加载一次，并用 `tokenConsumed` 标记后走 cookie 会话；
- 孤儿 node 进程占端口：启动前按 `dsh-node.pid` 清理；
- 旧 `cordis.patch.yml` 不更新：加 `WorkBuddy-managed-patch: v2` 标记强制刷新；
- `office-to-pdf` 插件因官方 payload 缺 `restructure/src/EncodeStream.js` 反复报错：补丁禁用该插件。

---

## 2.0.1 — 2026-10-19

### 修复
- **真机首启动崩溃/报错**：`Overview.inFlightFull` 声明为 `Boolean`，服务端返回整型计数
  → `Unexpected JSON token at offset 91 … $.in_flight_full`。改为 `Int`，并更新 UI 文案与用例。
- **16 KB 页对齐**：`libwb2api.so` 的 `LOAD` 段按 16 KB 对齐重建
  （`-Wl,-z,max-page-size=16384`），`zipalign -c -P 16` 通过。
- live fixture 的 UTF-8 BOM 导致解析失败：读取时 `removePrefix("\uFEFF")`。

### 其它
- 单元测试扩到 41 个（新增 11 个真机响应 fixture、SSE 客户端用例）。

---

## 2.0.0 — 2026-10-19

### 从 Web 面板改造为一体化 Android 应用
- **内置网关**：Go 服务端交叉编译为 `libwb2api.so`（cgo、`GOOS=android`、`-tags timetzdata`），
  由 `LocalGatewayManager` 拉起：随机 `sk-` Key、`127.0.0.1` 监听、健康检查、端口改写、日志轮转、接管已有实例；
  `GatewayService` 前台服务保活。
- **移动端 UI（Compose）**：6 个一级 Tab（总览/账号/助手/任务/日志/设置）+ 5 个二级页
  （服务器配置/用量/套餐/模型探测/券码），全部按手机屏幕与手势重排。
- **原生助手**：OpenAI 兼容 SSE 对话、多会话持久化、模型选择、系统提示词；
  可选无障碍工具调用（`screen/tap/type/scroll/back/home`，最多 4 轮）。
- **远程模式**：填写已有 `workbuddy2api` 实例地址与 Key 直连。
- **演示模式**：不联网预览界面。

### 工程
- Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01；minSdk 24、targetSdk 35。
- `jniLibs { useLegacyPackaging = true }`、ABI 过滤 `arm64-v8a` + `x86_64`、core library desugaring。
- 单元测试（27 个）+ lint 0 error，产出 `WorkBuddy-2.0.0.apk`。
