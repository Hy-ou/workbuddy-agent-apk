# WorkBuddy 3.13.4

四个 open issue 全部处理：三个真 bug 修复 + 一个功能缺口补齐。

## 修复：软键盘（issue #1 / #2）

**同一个缺陷的两个 API 分支**：`enableEdgeToEdge()` 从工程骨架起就有，而 DSH 分支从来没有
消费过 IME insets（`git log -S imePadding` 只有助手页与连接页）。

| API | 表现 | 原因 |
|---|---|---|
| ≤ 29 | 底部 6 Tab 上移到键盘上方，打字误触 | `adjustResize` 压的是窗口，`Scaffold.bottomBar` 跟着上移 |
| ≥ 30 | 输入框被键盘盖住 | `decorFitsSystemWindows=false` 后窗口不再压缩，IME 以 insets 下发，没人消费 |

- 新增 `ui/shell/ImeState.kt#rememberImeVisible()`：API 30+ 读 `WindowInsets.isImeVisible`；
  API < 30 用 `getWindowVisibleDisplayFrame()` 的**历史最大可见底部**做帧差兜底
  （分屏/小窗不会误判）。不抢 `AndroidComposeView` 的 insets 监听器 —— 那会让全 App 的 insets 失效。
- `AppShell`：键盘态隐藏底部 Tab，内容 `imePadding()` 顶到键盘上沿；Tab 高与键盘高互斥。
- `AppShell`：DSH 页补返回键处理 —— 标准布局下侧滑/返回回「总览」，不再一次误触就 finish 掉带内核的 App。

**次生 bug 一并修**：`VH_FIX_JS` 原本用 `document.documentElement.clientHeight` 量视口，但
`html` 高度被自己钉成固定 px 后 `clientHeight` 就冻结在旧值，`ResizeObserver` 观察的又是
`documentElement` 的盒子 —— 键盘让 WebView 变矮也不会重算。改为
`visualViewport.height || innerHeight`，并**无条件**注册 `resize` / `visualViewport.resize`。

## 修复：WebView 渲染进程死亡导致闪退（issue #4）

根因确认：全仓 **0 处** `onRenderProcessGone`。渲染进程异常退出后 Chromium 判定「没有 WebView
认领这次死亡」，直接 abort 宿主进程：

```
Renderer process (6622) crash detected (code -1).
Render process (6622) kill (OOM or update) wasn't handed by all associated webviews, killing application.
```

- `DshWebView` 的 `WebViewClient` 覆写 `onRenderProcessGone` 并**返回 `true`**；记录
  `crashed`（`didCrash()`；`false` = 被系统清理）与 `killedBySystem`，抢救 crashpad 转储后
  用 `generation` 作为 `key` **整实例重建** WebView（死实例连 `loadUrl` 都不可用）。
- 重建走 `DshManager.webUrl()`：token 已消费时自动退回 cookie 会话的 base URL，不重放一次性 token。
- **覆盖全部实例**：`DiagnosticsCard` 里取 UA 用的瞬时 WebView 也挂上返回 `true` 的 client
  （「all associated webviews」按实例算，漏一个照样 abort）。
- 降概率：`setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)`（API 26+）；
  前后台切换时 `onPause()/onResume()`（不用 `pauseTimers()`，它对全 App 生效会打断流式输出）。

## 新增：崩溃取证

- `WbApplication` 用 `ApplicationExitInfo`（API 30+）读回最近 5 次进程退出原因，落到
  `files/exit-info.log`（CRASH / CRASH_NATIVE / LOW_MEMORY / SIGNALED / ANR…），不再依赖
  root/Shizuku 翻 tombstone。
- `WebViewDumps.salvage()`：crashpad 转储写在 `cache/WebView/Crashpad`，保留策略由 WebView
  提供方与系统缓存清理决定（App 改不了），因此在崩溃瞬间抢拷到 `files/webview-crash/`，
  保留 7 天 / 最多 40 个文件。

## 新增：账号凭证导出（issue #3）

网关早已实现导出（`GET /panel/api/accounts/{uid}/export`、`GET /panel/api/accounts/export`），
Web 面板也有「导出 / 批量导出」按钮，只有 Android 面板没有入口 —— 属功能缺口，本次补齐：

- `WbApi.getRaw()` 读 `Content-Disposition` / `X-Exported-Count` / `X-Skipped-Count`；
  `WbRepository.exportAccount(uid)` / `exportAll()` 落到两个 UI 入口：
  - 账号页溢出菜单 →「导出全部凭证」
  - 账号详情弹层 →「导出凭证（含 token）」
- 用 SAF `CreateDocument` 落盘；旧网关没有该路由时提示「请升级网关或改用内置网关」；
  文件含明文 token，成功提示里带保管提醒。

## 版本与验证

- 版本 **3.13.4 / versionCode 33**
- `testDebugUnitTest` **61 项通过**（新增 3 项导出契约测试：文件名/计数解析、兜底文件名、旧网关 404）
- `lintDebug` **0 error**（18 条既有 warning，均为依赖升级提示与历史告警）
- `:app:assembleDebug` / `:app:assembleRelease` 均通过
- **未做真机验证**（本机未连设备），上线前建议重点跑：
  1. Android 10 机型：DSH 页点输入框 → 底部 Tab 消失、输入框贴键盘；收起键盘 Tab 回来；
  2. Android 11+ 机型：标准布局与沉浸全屏各点一次输入框 → 输入可见、无空白条；
  3. 对 WebView 渲染进程 `kill -9` → App 不退出，日志出现 `onRenderProcessGone crashed=…`，
     页面自动重载；
  4. 账号页导出 → 用「添加账号 → 导入 JSON」原样导回（闭环校验格式）。

## 安装

```bash
adb install -r WorkBuddy-3.13.4.apk
```

- 正式包：`WorkBuddy-3.13.4.apk`（116.9 MB，debug 签名）
- 调试包：`WorkBuddy-3.13.4-debug.apk`（128.0 MB）
- SHA-256 见 `SHA256SUMS`
