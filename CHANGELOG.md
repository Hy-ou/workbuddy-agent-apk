# 开发记录（CHANGELOG）

> 版本号对应 `android/app/build.gradle.kts` 的 `versionName`；日期为开发/验证时间（Asia/Shanghai）。
> 详细问题排查过程见 [docs/troubleshooting.md](docs/troubleshooting.md)。

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
