# 参考项目能力评估：deepseek-harness-android-app → 本项目的可整合项

参考项目：[woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app)（MIT，
v1.20.1）。它是一套**围绕 DSH 内核的完整 Android 宿主**，沉淀了大量真机适配经验。
本文逐项对照本项目（WorkBuddy APK 3.2.0）的现状，给出可整合清单、落点与优先级。

> 说明：参考项目的「手机能力」主要来自 5 个自定义 DSH 插件（`dsh-tool-shizuku` / `android` /
> `accessibility` / `vscreen` / `browser`），本仓库不打包这些插件；本文只评估**宿主 App 层**能力。

---

## 1. 现状对照

| 能力 | 参考项目 | 本项目 3.2.0 |
|---|---|---|
| DSH 内核独立进程 | ✅ | ✅ `dsh/DshManager.kt` |
| node 运行时放 jniLibs | ✅ | ✅ ELF 依赖改名后打包 |
| 一次性 token → WebView | ✅（探测不吃 token） | ✅ `tokenConsumed` |
| 内核树解压/自愈 | ✅ 清单校验 + 一键重解压 | ⚠️ 仅 `.extracted` 标记，无校验/无修复入口 |
| 引擎前台保活 | ✅ `EngineService` | ❌ DSH 无前台服务（网关有） |
| 引擎看门狗/自动重启 | ✅ | ❌ 仅在页面操作时启动 |
| 崩溃捕获 | ✅ `installCrashHandler` | ❌ |
| 首次权限引导（9 项） | ✅ `buildGuidePages` | ⚠️ 分散在设置页，无向导 |
| 所有文件访问 | ✅ | ✅ `StorageAccess` |
| 电池优化白名单引导 | ✅ `checkBatteryOptimization` | ❌ |
| WebView 版本检测 | ✅ `checkWebViewCompat`（Chromium 80+） | ❌（本项目已踩 `vh=0` 坑，更该加） |
| 系统 CA 合成 bundle | ✅ `ensureSystemCaBundle` | ⚠️ 仅内置 curl cacert.pem |
| 状态栏跟随页面底色 | ✅ `dshshell` JS 桥 + MutationObserver | ❌ |
| 日志导出/分享 | ✅ `LogShareProvider` | ⚠️ 仅 App 内查看 tail |
| 检查更新 | ✅ GitHub Releases API | ❌ |
| 内核树外置（卸载不丢） | ✅ `/sdcard/DeepSeekHarness` | ❌ 在 `filesDir`（卸载即删） |
| 安全模式 / 备份导入导出 | ✅ | ❌ |
| 用户补丁回放 | ✅ `UserPatches` | ❌ |
| 插件开关 UI | ✅ 控制台「插件」页 | ⚠️ 仅托管 `cordis.patch.yml` |
| ABI 兼容检查 | ✅ `checkAbiCompat` | ❌ |
| 通知权限引导 | ✅ | ✅ |
| 无障碍服务 | ✅（并暴露给 DSH 插件） | ✅（仅原生助手内部使用） |
| Shizuku / root 特权通道 | ✅ | ❌（依赖其插件 + Shizuku SDK） |
| 内置 Python / npm / git | ✅ | ❌（已从 payload 剔除） |
| vscreen 虚拟屏 | ✅（LGPL-3.0） | ❌ |
| AI 浏览器（独立进程） | ✅ | ❌ |
| 控制台主题包（JSON/zip） | ✅ `ConsoleTheme` | ❌（固定主题） |

---

## 2. 建议整合（按优先级）

### P0 · 健壮性与可用性（低风险，1~2 天）

#### 2.1 引擎前台服务 + 看门狗
- **价值**：DSH 是长驻进程，当前只在页面里手动启动，挂后台/锁屏易被杀且不会自动恢复。
- **做法**：
  1. 新增 `dsh/DshService.kt`（仿 `EngineService`）：常驻通知「DSH 内核运行中」，`START_STICKY`；
     `DshManager.start()` 成功后 `startForegroundService`，`stop()` 时停服。
  2. `DshManager` 内加协程看门狗：每 10 s 检查 `aliveCompat()`；退出则按 1s/5s/15s 退避重启（最多 3 次），
     仍失败 → 状态置 `Failed` 并提示「可尝试重新解压内核」。
- **落点**：`dsh/DshService.kt`（新）、`DshManager.kt`、`AndroidManifest.xml`。

#### 2.2 全局崩溃捕获 + 上次异常提示
- **价值**：真机偶发崩溃时能留下现场，且用户知道发生了什么。
- **做法**：`WbApplication` 安装 `Thread.setDefaultUncaughtExceptionHandler`，
  写 `files/crash/last-crash.log`（时间/版本/堆栈），然后走默认处理；
  下次启动在「设置 → 关于」显示「上次异常：点此查看/分享」。
- **落点**：`WbApplication.kt`、`ui/settings/AboutScreen` 或设置页条目。

#### 2.3 内核自检 + 一键重新解压（自愈）
- **价值**：升级/异常退出后内核树可能不完整；现在只能清数据。
- **做法**：解压时写入 `PAYLOAD-MANIFEST.json` 的条目数与总字节日志；
  启动前做「快速校验」（文件数、关键文件存在性、`bin.js` 可读）；
  失败时 UI 提供「重新解压内核」：删除 `files/dsh/dshroot` 后重新解压（保留 `dshhome` 用户数据）。
- **落点**：`DshManager.kt`（`quickCheck()` / `resetKernelTree()`）、`DshScreen` 失败态按钮。

#### 2.4 日志导出/分享
- **价值**：用户反馈问题时可一键给出网关 + DSH 日志。
- **做法**：`FileProvider` + `ACTION_SEND`，把 `gateway.log`、`dsh-web.log`、
  `last-crash.log` 打包 zip（或直接多文件）分享；同时保留 App 内 tail 视图。
- **落点**：新增 `util/LogShare.kt` + `res/xml/file_paths.xml` + Manifest provider。

#### 2.5 首次启动权限引导（裁剪版）
- **价值**：把分散的授权聚合成一次「体检」，降低上手成本。
- **做法**：复用参考项目 `StatusProvider` 模式（状态查询 + 一键跳转 + 完成后打勾）：
  本项目只需 5 项 → 通知、所有文件访问、忽略电池优化、无障碍（可选）、WebView 版本。
  UI 落在设置页顶部卡片「运行体检」，或首启弹一次性向导。
- **落点**：`ui/settings/`（新 `DiagnosticsCard.kt`）、`ui/dsh/DshScreen` 提示条复用。

---

### P1 · 体验与兼容（中风险，2~4 天）

#### 2.6 系统 CA 合成 bundle
- **价值**：企业代理/自签证书环境下，DSH 的 curl/git/网页请求可用（参考项目为此修过证书链）。
- **做法**：读 `/system/etc/security/cacerts/*.0` 合并成 `runtime/etc/cacert-system.pem`
  （带 stamp 缓存），`SSL_CERT_FILE` 优先指向它，失败回退内置 `cacert.pem`。
- **落点**：`DshManager.doStart()` 环境变量准备；新增 `dsh/SystemCaBundle.kt`。

#### 2.7 WebView 兼容自检（本项目刚踩过坑）
- **价值**：`vh/dvh` 为 0、Chromium 过旧都会导致控制台错乱；提前提示可避免用户误判「App 坏了」。
- **做法**：启动 WebView 前取 UA 里的 Chrome 主版本（<80 提示升级 WebView）；
  页面加载后执行一次 `CSS.supports('height','100vh')` + 实测高度自检，
  异常时标记「已启用视口修复」并在日志中说明（现有 `VH_FIX_JS` 已兜底）。
- **落点**：`ui/dsh/DshScreen.kt`。

#### 2.8 状态栏/导航栏跟随页面底色
- **价值**：DSH 深色/浅色主题切换时，状态栏不再割裂（参考项目 `dshshell.onBg`）。
- **做法**：复用现有注入通道，在 `VH_FIX_JS` 里追加 `MutationObserver` 监听 `body` 背景色，
  通过 `addJavascriptInterface(wbshell, "wbshell")` 回调 Kotlin，设置 `WindowInsetsController` 外观。
- **落点**：`DshScreen.kt` + `MainActivity` 的系统栏样式工具。

#### 2.9 电池优化白名单 + 更新检查
- **电池**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（需声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），
  放在「运行体检」里。
- **更新**：`GET api.github.com/repos/Hy-ou/workbuddy-agent-apk/releases/latest` 比对 `versionName`，
  设置页「检查更新」跳浏览器下载。
- **落点**：`ui/settings/`、`util/Update.kt`。

#### 2.10 内核树外置（省内部存储 / 重装不丢）
- **价值**：内核树约 420 MB，放 `filesDir` 吃内部空间且卸载即删；参考项目放在
  `/sdcard/DeepSeekHarness`。
- **做法**：解压目标改为 `/sdcard/WorkBuddy/dshroot`（依赖「所有文件访问」，未授权回退 `filesDir`）；
  迁移旧目录 + `DSH_HOME` 保持内部（用户会话/凭证留存策略需明确）。
- **风险**：外部存储权限撤销后内核不可用，需要降级策略；卸载残留需提供「清理」入口。
- **落点**：`DshManager`（root 路径选择 + 迁移）。

---

### P2 · 进阶能力（较大工程量，按需）

| 项 | 做法 | 风险/成本 |
|---|---|---|
| 插件开关 UI | 写 `cordis.patch.yml` 的 `disabled` 列表，重启内核生效 | 需与 DSH 配置格式绑定，升级易碎 |
| 安全模式 | 起不来时把 `dshhome/profiles` 用户层旁置，用出厂 patch 启动（保留会话/凭证） | 需要失败计数与恢复路径设计 |
| 备份导出/导入 | 打包 `dshhome` + `agent/sessions.json` 为 zip，SAF 选择保存/导入 | 会话含凭证，需提示脱敏 |
| 用户补丁回放 | 记录出厂基线，启动时回放用户改动（仅文本文件，≤8 MB） | 需要基线仓库，工程量最大 |
| ABI 提示 | 启动检查 `Build.SUPPORTED_ABIS` 不含 arm64 时提示「DSH 不可用」 | 10 行代码，顺手做 |

---

## 3. 不建议整合

| 项 | 原因 |
|---|---|
| vscreen 虚拟屏 | 移植自 Operit，**LGPL-3.0**；需要 Shizuku/root 起 shell 服务端；体积与合规成本高 |
| Shizuku / root 特权通道 | 依赖其 `dsh-tool-shizuku` 插件 + Shizuku SDK + 用户另行安装 Shizuku；本项目已有无障碍替代 |
| AI 浏览器（`dsh-tool-browser`） | 独立 `:browser` 进程 + GeckoView/WebView 双实现，工程量与体积都大 |
| 内置 Python / npm / git | 本项目 payload 刻意剔除（+250 MB），与本项目「轻量一体化」定位冲突 |
| GeckoView 兼容版 | +250 MB、minSdk 提到 26，仅为极老 WebView 设备；本项目改用视口修复规避 |
| 控制台主题包（JSON/zip） | 固定主题已满足需求，主题包系统（`ConsoleTheme` 60 KB）维护成本高 |
| 悬浮球 / 桌面小鲸鱼 | 与本项目面板式交互定位不同 |

---

## 4. 建议迭代

1. **迭代 A（P0）**：2.1 前台服务+看门狗 → 2.3 内核自检/重解压 → 2.2 崩溃捕获 → 2.4 日志导出 → 2.5 权限体检。
2. **迭代 B（P1）**：2.7 WebView 自检 → 2.6 系统 CA → 2.8 状态栏跟随 → 2.9 电池/更新 → 2.10 内核外置。
3. **迭代 C（按需）**：插件开关、安全模式、备份、用户补丁。

---

## 5. 参考实现索引（便于对照移植）

| 参考文件 | 相关内容 |
|---|---|
| `EngineService.java` | 前台服务模板（`START_STICKY` + 通知刷新） |
| `MainActivity.java` | `checkWebViewCompat` / `checkBatteryOptimization` / `checkAbiCompat` / `installCrashHandler` / `ensureSystemCaBundle` / `buildGuidePages`（9 项引导）/ `checkForUpdate` / 安全模式与备份 |
| `KernelSelfCheck.java` | 内核树清单校验（`manifestAvailable` / `run` / `sha256Of`） |
| `LogShareProvider.java` | `content://<pkg>.logshare` 日志/主题/备份分享 |
| `ConsoleTheme.java` | 控制台主题 JSON、导出导入 |
| `UserPatches.java` | 用户补丁基线（`.baseline.tsv`、`manifest.json`、8 MB 上限） |
| `SessionHeal.java` | 混装内核树自愈（只清 `@deepseek-ai/dsh*` 陈旧包） |
| `PayloadScript.java` | 引擎启动脚本与 node 环境变量 |
