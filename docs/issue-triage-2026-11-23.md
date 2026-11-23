# Issue 分诊报告（2026-11-23）

对 `Hy-ou/workbuddy-agent-apk` 当前 4 个 open issue（无已关闭 issue）逐条对照代码核查。
每条给出：现象 → 代码定位 → 根因 → 判定 → 修复方案。

基线：`86c6e61`（3.13.3 / versionCode 32，compileSdk 35，minSdk 24）。

> **实施状态（2026-11-23 追加）**：本报告的四条结论已全部落进 3.13.4 / versionCode 33 ——
> #4 的 `onRenderProcessGone`（含实例重建、第二个 WebView 覆盖、渲染优先级、前后台暂停）、
> #1+#2 的 IME 处理与 `VH_FIX_JS` 测量源、崩溃取证（ApplicationExitInfo + crashpad 抢拷）、
> #3 的凭证导出（`WbApi.getRaw` → `exportAccount/exportAll` → 账号页与详情页两个 SAF 入口）。
> 下文的代码片段是**方案原型**，最终实现以源码为准；两处与原方案不同：
> ① 平台**没有** `RenderProcessGoneDetail.didCrashDueToOOM()`，只有 `didCrash()`
> （`false` 即「被系统清理」），实现里改为记录 `crashed` / `killedBySystem` 两个字段；
> ② `rememberImeVisible()` 改用 `WindowInsets.isImeVisible` + `getWindowVisibleDisplayFrame()`
> 帧差，不再劫持 `AndroidComposeView` 的 insets 监听器（那会让全 App 的 insets 失效）。


---

## 0. 总表

| # | 标题 | 判定 | 优先级 | 一句话 |
|---|------|------|--------|--------|
| 1 | 键盘弹出时底部 Tab 上移 + 侧滑退出 App | **真 bug（两处独立缺陷）** | P1 | API ≤ 29 窗口被 IME 压缩，`Scaffold` 的 bottomBar 跟着上移；DSH 页没有返回键处理，侧滑 = finish Activity |
| 2 | 3.13.3 输入框被键盘遮挡 | **真 bug（与 #1 同源的另一半）** | P1 | API ≥ 30 edge-to-edge 下窗口不再压缩，DSH 内容区全仓唯一没有 `imePadding()`，页面底部被键盘压住 |
| 3 | 能加个导出账号吗 | **需求缺口（不是 bug）** | P2 | 网关早已实现导出接口、Web 面板已有「导出 / 批量导出」按钮，Android 面板 0 入口 |
| 4 | DSH 运行时闪退，疑似 WebView | **真 bug（报告结论正确）** | **P0** | 全仓 0 处 `onRenderProcessGone`，渲染进程死掉后 Chromium 直接 abort 宿主进程 |

三个真 bug 里，#4 是唯一会「丢用户工作、退出 App」的，先修；#1/#2 是同一个布局缺陷的两个 API 分支，一起修；#3 是纯增量功能。

---

## 1. Issue #1 —— 底部 Tab 随键盘上移 / 侧滑退出 App

### 现象
- 3.10.0（小米 mix2s，Android 10 / API 29）点输入框，底部 6 个 Tab 整体上移到软键盘上方，打字误触。
- 同一条评论补充：侧滑一下直接退出 App。
- 期望：Tab 被键盘覆盖或自动隐藏，输入框贴键盘上沿。

### 代码定位

| 位置 | 内容 |
|------|------|
| `android/app/src/main/AndroidManifest.xml` `MainActivity` | `android:windowSoftInputMode="adjustResize"` |
| `MainActivity.kt:40` | `enableEdgeToEdge()` → `WindowCompat.setDecorFitsSystemWindows(window, false)` |
| `ui/shell/AppShell.kt:117-141` | `Scaffold(bottomBar = { NavigationBar { ... } })`，未做任何 IME 处理 |
| `ui/shell/AppShell.kt:144-148` | 内容 `Box` 只吃 `padding.calculateBottomPadding()`，无 `imePadding()` |
| `ui/shell/AppShell.kt:113-115` | `BackHandler(enabled = route != Route.Main)`、`BackHandler(enabled = hideChrome)` |

### 根因

底部 Tab 不是被「顶上去」的，是**整个窗口被系统压扁了**：

- `adjustResize`（API ≤ 29）在键盘弹出时压缩 Activity 窗口高度；
- `enableEdgeToEdge()` 在 API ≤ 29 只加 `LAYOUT_*` 系统栏标志，**不影响 IME 的窗口压缩**；
- Compose 根布局高度 = 压缩后的窗口高度 → `Scaffold` 重新布局 → `NavigationBar` 落到「窗口底部」，也就是键盘上沿。

Material3 1.3.1（compose-bom 2024.10.01）里 `ScaffoldDefaults.contentWindowInsets` = `WindowInsets.systemBars`，`NavigationBarDefaults.windowInsets` = `systemBars`，**都不含 `ime`**，所以框架不会替我们处理。

反证：同一个仓库里另外两个输入区是显式处理过的 ——
`ui/assistant/AssistantScreen.kt:367` `Column(Modifier.fillMaxSize().imePadding())`、
`ui/connect/ConnectScreen.kt:92` `.imePadding()`。
`git log -S imePadding` 只有这两处引入（`b2d3d96` / `2e4a445`），**DSH 分支从来没有过 IME 处理**。

侧滑退出是另一个独立缺陷：标准布局下 `route == Route.Main` 且 `!hideChrome`，两个 `BackHandler` 都 `enabled = false` → 系统返回交给框架 → 根 Activity `finish()` → App 退出。用户只是从屏幕边缘划了一下。

### 判定
**真 bug × 2**（布局 + 返回键），与代码完全对得上，可以复现。

### 修复方案
见 §2.F2（IME 布局）与 §2.F6（返回键）。

---

## 2. Issue #2 —— 3.13.3 输入框被键盘遮挡

### 现象
3.13.3（v32）Android 某机（1080×2280），进 DSH → 打开会话 → 点底部输入框 → 键盘弹起后输入框被压在键盘下，键盘上方只剩会话主页。

### 代码定位

| 位置 | 内容 |
|------|------|
| `ui/dsh/DshScreen.kt:115-163` | DSH 页根 `Box`/`Column`，无任何 inset 处理 |
| `ui/dsh/DshScreen.kt:145-155` | `Box(Modifier.weight(1f).fillMaxWidth()) { DshContent(...) }` —— WebView 就是这块 |
| `ui/dsh/DshScreen.kt:908` | 全文件唯一一处 IME 读取：`WindowInsets.isImeVisible`，**只用于沉浸态重隐藏状态栏**，不影响布局 |
| `ui/shell/AppShell.kt:144-148` | 内容 `Box` 只应用了 Scaffold 的 bottom padding |

### 根因

**和 #1 是同一个缺陷的另一个 API 分支。** `setDecorFitsSystemWindows(false)` 在 API ≥ 30 生效后，键盘不再压缩窗口，IME 改为以 `WindowInsets.ime` 下发，必须由 App 自己消费。App 从来没消费过 → 窗口保持全高 → 页面底部（含输入框）被键盘盖住。

不是 3.13.x 引入的回归：3.13.3 只是把「默认标准布局」变成默认值，标准布局下 DSH 页要同时容纳顶栏 + 底部 Tab，可视高度比沉浸态更小，撞上概率因此变高。缺陷本身在 3.10.0 就已经存在（那时是 #1 的形态）。

### 次生 bug（必须一起修，否则主修复无效）

`ui/dsh/DshScreen.kt:715-805` 的 `VH_FIX_JS` 把 `html/body/#root` 高度钉成固定 px：

```js
var h = document.documentElement.clientHeight || window.innerHeight || 0;   // 751
...
st.textContent = 'html,body,#root{height:' + H + 'px !important;...'      // 763
```

一旦 `html{height:Npx}` 被写下，`document.documentElement.clientHeight` 就等于 `N`
（元素有显式高度时 `clientHeight` 返回自身 padding box，不再代表视口），
而 `ResizeObserver` 观察的正是 `documentElement` 的 border-box，**永远是 N**。
`window.addEventListener('resize')` 只在 `ResizeObserver` 不可用时才注册（778-780）。

结论：**原生侧让 WebView 变矮之后，页面也不会重算高度**，输入框依旧钉在旧位置。
这个测量源必须改掉，否则 §2.F2 只解决一半。

### 判定
**真 bug**，且报告人看到的只是「现象那一侧」，根因在壳层。

### 修复方案
见 §2.F2 + §2.F3。

---

## 3. Issue #3 —— 导出账号

### 现象
附图「账号」页，要求「导出 wb 账号」。

### 实际情况：后端已就绪，只缺前端入口

| 层 | 状态 | 证据 |
|----|------|------|
| 网关（Go） | ✅ 已实现单个 + 批量导出 | `workbuddy2api-panel/internal/panel/export.go:20`、`:58`；路由 `panel.go:168-169` |
| Web 面板 | ✅ 已有按钮 | `app.js:487`（行内「导出」）、`index.html:678`（`批量导出`）、`app.js:54`（`download()` 带鉴权取 blob） |
| **Android 面板** | ❌ **完全没有** | `WbRepository` 只有 `importCockpit`；`AccountsScreen.kt` 溢出菜单只有 5 个批量动作；全仓 `导出` 只出现在 DSH 备份（`DshToolsCard.kt:179`） |
| 随包网关二进制 | ✅ 已含导出路由 | `jniLibs/arm64-v8a/libwb2api.so`（2026-11-06 00:41）字符串含 `accounts/export`、`X-Exported-Count`、`X-Skipped-Count` |

响应契约（`export.go`）：
- `GET /panel/api/accounts/{uid}/export` → 单账号嵌套形凭证 JSON，`Content-Disposition: attachment; filename="workbuddy-<uid>.json"`
- `GET /panel/api/accounts/export` → 数组，`filename="workbuddy-accounts-YYYYMMDD-HHMMSS.json"`，
  额外头 `X-Exported-Count` / `X-Skipped-Count`
- 凭证为空等异常账号计入 `skipped` 不阻断；`404 no accounts to export`；旧网关可能 404/501

### 判定
**不是 bug，是功能缺口**（Web 面板 ↔ Android 面板的功能不对齐）。
顺带一个隐患：`importCockpit` 的提示文案（`AccountsScreen.kt:788`、`:798`）已经在宣传
「本面板导出的凭证 JSON」，但面板里根本导不出来 —— 文案先行，功能没跟上。

### 修复方案
见 §2.F4。

> 安全提醒（要写进 UI 文案）：导出文件是 `accessToken` / `refreshToken` 明文，
> 与 Web 面板一致需要显式提示「请妥善保管」，且日志只记 UID 不落 token。

---

## 4. Issue #4 —— WebView 渲染进程死亡导致整个 App 闪退

### 报告结论复核
**成立，且代码侧完全对得上。**

| 报告论断 | 代码核查 |
|----------|----------|
| 宿主没接管渲染进程死亡 | 全仓 `grep onRenderProcessGone` → **0 处命中**；`DshScreen.kt:445-484` 的 `webViewClient = object : WebViewClient()` 只覆写了 `shouldOverrideUrlLoading` / `onPageFinished` / `onReceivedHttpError` / `onReceivedError` → 默认 `return false` → Chromium `abort()` 宿主 |
| 「all associated webviews」是复数，可能有多个实例 | 实际长活实例只有 1 个（`DshScreen.kt:424`，与报告 `Views: 39, WebViews: 1` 一致）；另有 1 个瞬时实例 `DiagnosticsCard.kt:65`（`remember{}` 里创建后立刻 `destroy()`）。**但瞬时那个也没注册 client**，理论上同窗口期内仍会拉全体下水 |
| 「假崩」：`crash detected (code -1)` 无 `killing application`，页面重载 App 存活 | 对应 `DshScreen.kt:409-419` 的 `DisposableEffect { onDispose { stopLoading(); removeView(); destroy() } }` —— 切 Tab/离开 DSH 页时主动销毁 WebView，正是「页面刷新、App 存活」的来源。报告的统计口径建议是对的 |
| 切会话是触发场景 | 切会话 = DSH 前端重建整页 DOM + 214 张样式表 + 渲染进程 PSS 348 MB（其中 194 MB `Unknown` 私有脏页、100 MB 已换出） |

### 对报告里三个提问的回答

1. **`code -1` / `code 5` 的语义**：日志里的 code 是**渲染进程的退出码**（信号编号）。
   `5` = `SIGTRAP`，即渲染进程自己 `IMMEDIATE_CRASH()` / `OOM_CRASH()`，crashpad 介入（对应报告 B 类）；
   `-1` = 拿不到有效退出码（进程被外部清理 / 状态不可得，对应 A 类被 lmkd 清理）。
   但**权威判别只有 `RenderProcessGoneDetail.didCrash()`**（平台并没有暴露
   `didCrashDueToOOM()`）：`true` = 渲染进程自身崩溃，`false` = 被系统清理（OOM/update）。
   把它和 `ApplicationExitInfo` 一起落日志，就能把两种形态永久分开。
2. **WebView 实例数量与销毁周期**：1 个长活 + 1 个瞬时；长活实例在**切 Tab 离开 DSH 页时销毁**，
   不做「旧未销毁/新已创建」的重叠 —— 报告这条风险在当前实现里不成立。真正的内存压力来自单实例自身。
3. **minidump 清理由谁触发**：由 WebView 提供方（crashpad handler）与系统缓存清理策略决定，
   **App 无法设置保留 7 天**。可做的是「抢在清空前把 `cacheDir/WebView/Crashpad/**` 拷进 `filesDir`」（§2.F5）。

### 判定
**P0 真 bug**，报告给出的修复方向（`onRenderProcessGone` 返回 `true` + 重建）是正确且必要的。

### 修复方案
见 §2.F1 / §2.F5 / §2.F6。

---

## 2. 修复方案（按优先级）

### F1（P0）接管渲染进程死亡 —— 治闪退

**改动点**：`ui/dsh/DshScreen.kt`（`DshWebView`，360-596）+ `ui/settings/DiagnosticsCard.kt:65`。

渲染进程死后旧 `WebView` 不可复用（`loadUrl` 也无效），必须整实例重建。
`AndroidView` 的 `factory` 只跑一次，所以用一个 `generation` 作为 `key` 触发重建：

```kotlin
// ui/dsh/DshScreen.kt

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DshWebView(
    manager: DshManager,
    onChromeMajor: (Int) -> Unit = {},
) {
    // 渲染进程死亡后旧 WebView 不可复用：用 generation 当 key，
    // 让它整实例重建（含 AndroidView 的 factory + 一次性 token URL 的重新解析）。
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        DshWebViewInstance(
            manager = manager,
            onChromeMajor = onChromeMajor,
            onRendererGone = { crashed, oom ->
                Log.e(TAG_WEB, "renderer gone didCrash=$crashed dueToOOM=$oom -> rebuild #${generation + 1}")
                generation++
            },
        )
    }
}

// 原 DshWebView 的函数体整体改名为 DshWebViewInstance，签名加一个回调：
@Composable
private fun DshWebViewInstance(
    manager: DshManager,
    onChromeMajor: (Int) -> Unit,
    onRendererGone: (crashed: Boolean, oom: Boolean) -> Unit,
) {
    // ... 保持现有 target / fileCallback / webHolder / DisposableEffect 逻辑不变 ...
    webViewClient = object : WebViewClient() {
        // ★ 返回 true = 「这次死亡我认领了」，阻止 Chromium abort 整个 App。
        //   不实现 / 返回 false = 现状 = 闪退。
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { detail.didCrash() }.getOrDefault(false)
            } else false
            Log.e(TAG_WEB, "onRenderProcessGone crashed=$crashed killedBySystem=${!crashed} url=${view.url}")
            // 抢在 crashpad 清空前捞转储（见 F5）
            runCatching { WebViewDumps.salvage(view.context.applicationContext) }
            // 不要在死实例上 loadUrl：交给上层切 generation 重建
            onRendererGone(crashed, !crashed)
            return true
        }
        // ... 其余覆写保持不变 ...
    }
}
```

要点：
- 需要补一个 import：`androidx.compose.runtime.key`（`DshScreen.kt` 当前 49-57 行没有它）。
- `onRenderProcessGone` 是 API 26+；`minSdk 24` 下该方法在 24/25 上**不会被调用**，直接写即可
  （`RenderProcessGoneDetail` 只在该方法被框架调用时才加载）。若担心校验器，把 `detail.*` 抽到
  `@RequiresApi(26)` 的私有函数里。
- 重建后 `manager.webUrl()` 会自动走 cookie 版 base URL（`DshManager.kt:108-112`：
  `tokenConsumed == true` 时返回 base），不会重放一次性 token。
- `DiagnosticsCard.kt:65` 的裸 WebView 也要挂一个返回 `true` 的 client：

```kotlin
val wv = android.webkit.WebView(context).apply {
    webViewClient = object : android.webkit.WebViewClient() {
        override fun onRenderProcessGone(view: android.webkit.WebView, detail: android.webkit.RenderProcessGoneDetail) = true
    }
}
```

### F2（P1）键盘 / IME 布局 —— 治 #1 + #2

新增 `ui/shell/ImeState.kt`，提供跨版本键盘可见性：

```kotlin
package com.workbuddy2api.panel.ui.shell

/**
 * 键盘是否可见（跨 API）。
 *  - API 30+ / edge-to-edge：窗口不再被 IME 压缩，读 WindowInsetsCompat 的 ime 高度；
 *  - API < 30：adjustResize 压的是窗口，ime insets 恒为 0，
 *    用「屏幕高 - getWindowVisibleDisplayFrame().bottom」的差值兜底。
 */
@Composable
fun rememberImeVisible(): Boolean {
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val rect = android.graphics.Rect()
        fun refresh(imeBottom: Int = -1) {
            view.getWindowVisibleDisplayFrame(rect)
            val screenH = view.resources.displayMetrics.heightPixels
            val hidden = (screenH - rect.bottom).coerceAtLeast(0)
            val byInsets = imeBottom > 0
            val byFrame = screenH > 0 && hidden > screenH * 0.15f   // 15% 阈值排除导航栏/手势条
            visible = byInsets || byFrame
        }
        val vto = view.viewTreeObserver
        val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { refresh() }
        vto.addOnGlobalLayoutListener(layout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            refresh(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        refresh()
        onDispose {
            runCatching { vto.removeOnGlobalLayoutListener(layout) }
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
        }
    }
    return visible
}
```

`ui/shell/AppShell.kt` 改三处：

```kotlin
    // 键盘弹出时：底部 Tab 让位（隐藏），内容顶到键盘上沿。
    // 用户期望见 issue #1：「被软键盘覆盖（或自动隐藏），输入框直接吸附在键盘上方」。
    val imeVisible = rememberImeVisible()
    val showBottomBar = route == Route.Main && !hideChrome && !imeVisible

    Scaffold(
        ...
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(...) { /* 原样不动 */ }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 有 Tab 时按 Tab 高度让位；键盘态下 Tab 已隐藏，改由 imePadding 让位，
                    // 两者互斥，避免「Tab 高 + 键盘高」双份留白。
                    .padding(bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp)
                    .imePadding(),
            ) { /* 原样不动 */ }
        }
    }
```

为什么这一处同时治 #1 和 #2：
- API ≥ 30：`.imePadding()` 把内容（含 DSH 的 WebView）压到键盘上沿 → #2 的输入框露出来；
  Tab 隐藏后被键盘完全覆盖 → #1 的误触消失。
- API ≤ 29：窗口被系统压缩，`imePadding` 拿到的 insets 为 0（无双重留白），
  `rememberImeVisible()` 走 frame 兜底 → Tab 被隐藏，不再「悬浮在键盘上方」。
- 嵌套不冲突：`imePadding()` 会消费 insets，`AssistantScreen.kt:367` / `ConnectScreen.kt:92`
  已有的 `imePadding()` 在壳层已经消费后自动变 0，不会双倍留白。

### F3（P1）修 `VH_FIX_JS` 的测量源 —— 让 F2 真正生效

`ui/dsh/DshScreen.kt:751`：

```js
- var h = document.documentElement.clientHeight || window.innerHeight || 0;
+ // html 被钉成 px 后 clientHeight 就冻结在旧值，必须量真正的视口。
+ var vv = window.visualViewport;
+ var h = Math.round((vv && vv.height) || window.innerHeight || document.documentElement.clientHeight || 0);
```

`ui/dsh/DshScreen.kt:776-781`：把 resize 监听改成**无条件注册**，并监听 visualViewport：

```js
  try { new ResizeObserver(function(){ window.__wbVhFix(); }).observe(document.documentElement); } catch (e) {}
+ window.addEventListener('resize', function(){ window.__wbVhFix(); }, { passive: true });
+ if (window.visualViewport) {
+   window.visualViewport.addEventListener('resize', function(){ window.__wbVhFix(); }, { passive: true });
+ }
  window.addEventListener('orientationchange', ...);
```

`apply()` 里的 `changed` 判定（753）不用改：高度真变了才会重写 style，性能约束保持。

### F4（P2）账号导出 —— 补 #3

**1) `data/WbApi.kt` 增加带响应头的 GET**

```kotlin
/** 原始响应：导出接口要读 Content-Disposition / X-Exported-Count。 */
class WbRawResponse(val body: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

suspend fun getRaw(path: String, timeoutSec: Long = 120): WbRawResponse = withContext(Dispatchers.IO) {
    val url = root.trimEnd('/') + "/" + path.trimStart('/')
    val builder = Request.Builder().url(url)
    if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
    val call = client.newCall(builder.get().build())
    call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
    try {
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 401) throw WbHttpException(401, text, "API Key 无效或已变更")
                throw WbHttpException(resp.code, text, parseErrorMessage(text) ?: "请求失败（HTTP ${resp.code}）")
            }
            WbRawResponse(text, resp.headers.toMap())
        }
    } catch (e: WbHttpException) { throw e }
      catch (e: IOException) { throw IOException("无法连接服务器，请检查网络与地址", e) }
}
```

**2) `data/Repository.kt` 接口 + `RemoteRepository.kt` 实现**

```kotlin
// Repository.kt
/** 凭证导出结果：content 是网关原样返回的 JSON（含 accessToken 明文）。 */
data class ExportResult(val fileName: String, val content: String, val exported: Int, val skipped: Int)

suspend fun exportAccount(uid: String): ExportResult
suspend fun exportAll(): ExportResult
```

```kotlin
// RemoteRepository.kt
private fun WbRawResponse.toExport(fallbackName: String): ExportResult {
    val cd = header("Content-Disposition").orEmpty()
    val name = Regex("filename=\"?([^\";]+)\"?").find(cd)?.groupValues?.get(1) ?: fallbackName
    return ExportResult(
        fileName = name,
        content = body,
        exported = header("X-Exported-Count")?.toIntOrNull() ?: 0,
        skipped = header("X-Skipped-Count")?.toIntOrNull() ?: 0,
    )
}

override suspend fun exportAccount(uid: String): ExportResult =
    api.getRaw("accounts/${enc(uid)}/export").toExport("workbuddy-$uid.json")

override suspend fun exportAll(): ExportResult =
    api.getRaw("accounts/export").toExport("workbuddy-accounts.json")
```

`DemoRepository.kt` 补两个 `override`（返回 `ExportResult("workbuddy-demo.json", "[]", 0, 0)` 或直接抛「演示模式不支持导出」）。

**3) UI：`ui/accounts/AccountsScreen.kt`**

- 溢出菜单（194-205 的 `DropdownMenu`）加一项「导出全部凭证」；
- 用 SAF 落盘，照抄 `DshToolsCard.kt:66-75` 的写法：

```kotlin
val context = LocalContext.current          // AccountsScreen 顶部目前没有声明，需补
var exporting by remember { mutableStateOf(false) }
var pendingExport by remember { mutableStateOf<ExportResult?>(null) }
val exportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    val data = pendingExport
    pendingExport = null
    if (uri == null || data == null) return@rememberLauncherForActivityResult
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(data.content.toByteArray()) }
                    ?: error("无法写入所选文件")
            }
            snackbar.showSnackbar("已导出 ${data.exported} 个账号（${data.fileName}）" +
                if (data.skipped > 0) "，跳过 ${data.skipped} 个" else "")
        }.onFailure { snackbar.showSnackbar("导出失败：${it.message}") }
        exporting = false
    }
}

fun exportAll() {
    if (exporting) return
    exporting = true
    scope.launch {
        try {
            val r = repo.exportAll()
            pendingExport = r
            exportSaver.launch(r.fileName)     // 服务端已生成时间戳文件名
        } catch (e: Exception) {
            exporting = false
            snackbar.showSnackbar(acctErrorMessage(e))
        }
    }
}
```

- `AccountDetailSheet` 的「操作」区（`AccountDetailSheet.kt:271+`）单独加一个「导出凭证」按钮，
  单账号走 `repo.exportAccount(uid)`，同一套 SAF 落盘。
- 兼容旧网关：`WbHttpException(404/501)` 时提示「当前服务器不支持凭证导出，请升级网关或改用内置网关」
  （与现有 `importCockpit` 的 788 行文案同一风格）。
- 文案必须写「含 token，请妥善保管」。

### F5（P1）崩溃取证 —— 让下次能定位

**a) `ApplicationExitInfo`（API 30+）**，`WbApplication.kt` 现在是空壳：

```kotlin
class WbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        recordExitReasons()
    }

    /** 上次进程为什么死的：REASON_CRASH / REASON_CRASH_NATIVE / REASON_LOW_MEMORY / REASON_SIGNALED。 */
    private fun recordExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val log = File(filesDir, "exit-info.log")
            am.getHistoricalProcessExitReasons(packageName, 0, 5).forEach { info ->
                if (info.reason == ApplicationExitInfo.REASON_USER_REQUESTED) return@forEach
                log.appendText(
                    "${info.timestamp} reason=${info.reason} status=${info.status} " +
                        "desc=${info.description} pss=${info.pss} rss=${info.rss}\n"
                )
            }
        }
    }
}
```

对应报告里「P1 · 采集 ApplicationExitInfo」那条，且不需要 root/Shizuku。

**b) minidump 抢救（诚实版）**
crashpad 的保留策略由 WebView 提供方控制，App 设不了「保留 7 天」。能做的是抢在清理前拷贝：

```kotlin
object WebViewDumps {
    fun salvage(context: Context, keepDays: Long = 7) {
        val src = File(context.cacheDir, "WebView/Crashpad")
        if (!src.isDirectory) return
        val dst = File(context.filesDir, "webview-crash").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - keepDays * 24 * 3600_000L
        dst.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        src.walkTopDown().filter { it.isFile && it.length() > 0 }.forEach { f ->
            runCatching {
                f.copyTo(File(dst, "${System.currentTimeMillis()}-${f.parentFile?.name}-${f.name}"), overwrite = true)
            }
        }
    }
}
```

调用点：`onRenderProcessGone` 内 + `WbApplication.onCreate`。

### F6（P2）加固项

1. **返回键**（#1 的第二半）：`AppShell` 增加

```kotlin
// DSH 是二级页：侧滑/返回先回「总览」，不要直接把带内核的 App 干掉。
BackHandler(enabled = tab == 2 && engine == "dsh" && !hideChrome && !imeVisible) { tab = 0 }
```
（放在现有两个 `BackHandler` 之前注册，语义更具体。想更贴近浏览器手感，可把 `WebView.goBack()`
暴露成 `DshManager` 上的一个回调，有历史就先 `goBack()`。）

2. **渲染进程优先级**：`DshScreen.kt:424` 的 `WebView(ctx).apply{}` 里加

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
}
```

3. **前后台暂停渲染**：给 DSH 页挂 `LifecycleEventObserver`，`ON_STOP → webView.onPause()`、
   `ON_START → webView.onResume()`。**不要**用 `pauseTimers()`（全 App 生效，会打断 DSH 的流式输出）。

4. **内存峰值（报告 P2）**：`offscreenPreRaster = true`（`DshScreen.kt:441`）在有大量合成层时
   会显著推高渲染进程的 `Unknown` 私有页，建议做一次 A/B（上报数据里 194 MB `Unknown` 私有脏页
   是最大项）。214 张样式表、切会话重建整页 DOM 属 DSH 前端，壳层只能靠 `onPause` + 及时 `destroy()` 缓解。

5. **统计口径**（报告 P1 第三条）：`onRenderProcessGone` 里的 `crashed` / `killedBySystem`
   已经能把「自身崩」和「被系统清理」分开，配合 F5 的 `exit-info.log`，「真崩 / 假崩（主动 `destroy()`）」
   可以直接从两份日志对出来，不需要靠 logcat 人工筛。

---

## 3. 验证清单

构建：

```powershell
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest        # 现有 58 项
./gradlew :app:lintDebug
```

真机：

1. **#1**：API 29 及以下机型 → 助手(DSH) → 点输入框 → 底部 Tab 应消失，输入框贴键盘上沿；
   收起键盘 Tab 回来。
2. **#2**：API 30+ 机型 → 标准布局下点输入框 → 输入框完整可见，键盘上方无空白条、无 Tab。
   切沉浸全屏重复一次（沉浸态也必须能打字）。
3. **#3**：账号页 → 溢出菜单「导出全部凭证」→ 选保存位置 → 文件能被「添加账号 → 导入 JSON」原样导回
   （闭环校验，这是唯一能证明格式正确的自动化验证）。单账号详情页同样走一遍。
4. **#4**：`adb shell` 里对 WebView 渲染进程 `kill -9`，或用 devtools 让渲染进程 OOM：
   - 日志出现 `onRenderProcessGone didCrash=... dueToOOM=...`
   - **不再出现** `killing application` / `triggering application crash`
   - App 进程 pid 不变，页面自动重载，会话可继续。
5. `filesDir/exit-info.log` 与 `filesDir/webview-crash/` 里有留存（下次崩就能定位）。

---

## 4. 建议的 issue 回复要点

- **#1**：已确认是壳层 IME 处理缺失（不是 Tab 组件问题），与 #2 同源；一并修，附带把 DSH 页的
  返回键改成回「总览」。
- **#2**：3.13.3 不是引入方，是同一个缺陷在 Android 11+ 的表现；同时修 WebView 视口高度重算。
- **#3**：网关和 Web 面板都有导出，Android 侧漏了入口；按 Web 面板的交互补齐（单个 + 批量）。
- **#4**：结论采纳，`onRenderProcessGone` 返回 `true` + 重建；minidump「保留 7 天」改为
  「崩溃瞬间抢拷进私有目录」，并补 `ApplicationExitInfo`；code 语义按 `didCrash()`/`didCrashDueToOOM()` 落日志后即可定性。
