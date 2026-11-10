package com.workbuddy2api.panel.ui.dsh

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbuddy2api.panel.dsh.DshManager
import com.workbuddy2api.panel.dsh.DshManager.State
import com.workbuddy2api.panel.ui.components.AllFilesAccessBanner
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import kotlinx.coroutines.launch

/**
 * DSH（DeepSeek Harness）内核控制台：独立 node 进程 + WebView。
 *
 * 模型提供方在 DSH 控制台内配置为本地网关：http://127.0.0.1:7863/v1
 * （API Key 见「设置 → 连接」）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DshScreen(
    manager: DshManager,
    gatewayApiKey: String = "",
    immersive: Boolean = false,
    onImmersiveChange: (Boolean) -> Unit = {},
) {
    val wb = LocalWbColors.current
    val state by manager.state.collectAsStateWithLifecycle()
    var showLogs by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    // WebView 兼容自检（Chromium 版本）。页面底色不再走 Compose 状态：
    // 桥接回调直接改 WebView 背景与系统栏图标，避免整屏重组（见 DshWebView）。
    var chromeMajor by remember { mutableIntStateOf(0) }
    // 进入 DSH 页即自动拉起内核（未运行时），省掉一次点击。
    LaunchedEffect(Unit) {
        val cur = manager.state.value
        if (cur is State.Stopped || cur is State.Failed) {
            manager.startAsync().join()
        }
    }
    // 沉浸：隐藏系统状态栏/手势条；退出时恢复。离开 DSH 页也会恢复。
    ImmersiveSystemBars(immersive)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!immersive) {
                WbTopBar(
                    title = "DSH 内核",
                    subtitle = when (val s = state) {
                        is State.Running -> "独立进程 · ${manager.baseUrl() ?: ""}"
                        is State.Extracting -> "解压内核中…"
                        is State.Starting -> s.message
                        is State.Failed -> "启动失败"
                        else -> "未启动"
                    },
                    actions = {
                        IconButton(onClick = { onImmersiveChange(true) }) {
                            Icon(Icons.Outlined.Fullscreen, contentDescription = "全屏使用", tint = wb.ink2)
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = "网关接入信息", tint = wb.ink2)
                        }
                        IconButton(onClick = { showLogs = true }) {
                            Icon(Icons.Outlined.Description, contentDescription = "日志", tint = wb.ink2)
                        }
                        IconButton(onClick = { manager.restartAsync() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "重启", tint = wb.ink2)
                        }
                    },
                )
                AllFilesAccessBanner()
                DshChromeCompatBanner(chromeMajor)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                DshContent(
                    manager = manager,
                    state = state,
                    onChromeMajor = { if (chromeMajor != it) chromeMajor = it },
                )
            }
        }
        if (immersive) {
            DshExitFullscreenChip(
                onExit = { onImmersiveChange(false) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = wb.surface,
        ) {
            GatewayInfoSheet(apiKey = gatewayApiKey, onClose = { showInfo = false })
        }
    }

    if (showLogs) {
        ModalBottomSheet(
            onDismissRequest = { showLogs = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = wb.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("DSH 进程日志", style = MaterialTheme.typography.titleMedium, color = wb.ink)
                Text("dsh-web.log（最近 120 行）", style = MaterialTheme.typography.bodySmall, color = wb.ink3)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        manager.logTail().ifBlank { "暂无日志" },
                        style = MaterialTheme.typography.bodySmall,
                        color = wb.ink2,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                WbButton("关闭", onClick = { showLogs = false }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * DSH 内容区：按内核状态渲染 WebView / 解压 / 启动 / 失败 / 未启动。
 *
 * 独立成层，进出全屏只改变量出来的布局约束，不重建 WebView。
 */
@Composable
private fun DshContent(
    manager: DshManager,
    state: State,
    onChromeMajor: (Int) -> Unit,
) {
    val wb = LocalWbColors.current
    when (state) {
        is State.Running -> DshWebView(manager = manager, onChromeMajor = onChromeMajor)
        is State.Extracting -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.height(28.dp), strokeWidth = 3.dp, color = wb.accent)
            Spacer(Modifier.height(14.dp))
            Text(
                "首次启动：解压 DSH 内核（约 28k 个文件，几秒钟）",
                style = MaterialTheme.typography.bodyMedium,
                color = wb.ink2,
            )
            Text("之后启动将直接进入控制台", style = MaterialTheme.typography.bodySmall, color = wb.ink3)
        }
        is State.Starting -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.height(28.dp), strokeWidth = 3.dp, color = wb.accent)
            Spacer(Modifier.height(14.dp))
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = wb.ink2)
            Text(
                "正在拉起独立 node 进程并等待控制台就绪…",
                style = MaterialTheme.typography.bodySmall,
                color = wb.ink3,
            )
        }
        is State.Failed -> Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WbCard(Modifier.fillMaxWidth()) {
                Text("DSH 启动失败", style = MaterialTheme.typography.titleMedium, color = wb.bad)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink2,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(12.dp))
            WbButton(
                text = "重新启动",
                onClick = { manager.restartAsync() },
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // 连续失败时提供安全模式：用出厂 profile 启动，保留会话与凭证
            WbButton(
                text = "以安全模式启动（出厂配置）",
                onClick = { manager.startSafeModeAsync() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> DshIdleCard(manager)
    }
}

@Composable
private fun DshIdleCard(manager: DshManager) {
    val wb = LocalWbColors.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WbCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("DeepSeek Harness 内核", style = MaterialTheme.typography.titleMedium, color = wb.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "以独立 Node 进程运行完整 DSH Agent（会话、工具、终端、浏览器），" +
                    "与本 App 的网关/管理面板互不影响。模型提供方请在控制台内配置为本地网关。",
                style = MaterialTheme.typography.bodySmall,
                color = wb.ink2,
            )
            Spacer(Modifier.height(8.dp))
            Mono("控制台: http://127.0.0.1:8300")
            Mono("网关 API: http://127.0.0.1:7863/v1")
            Spacer(Modifier.height(14.dp))
            WbButton(
                text = "启动 DSH 内核",
                onClick = { manager.startAsync() },
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 在 DSH 控制台里配置模型提供方所需的网关信息（可复制）。 */
@Composable
private fun GatewayInfoSheet(apiKey: String, onClose: () -> Unit) {
    val wb = LocalWbColors.current
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("接入本地网关", style = MaterialTheme.typography.titleMedium, color = wb.ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "在 DSH 控制台 → 设置 → 模型 → 添加提供方（自定义 / OpenAI 兼容），填写下面两项；" +
                "模型 ID 使用网关返回的 cn:xxx / global:xxx。",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        Spacer(Modifier.height(12.dp))
        InfoCopyRow("Base URL", "http://127.0.0.1:7863/v1") {
            clipboard.setText(AnnotatedString("http://127.0.0.1:7863/v1"))
            scope.launch { snackbar.showSnackbar("已复制 Base URL") }
        }
        Spacer(Modifier.height(8.dp))
        InfoCopyRow("API Key", apiKey.ifBlank { "（未连接本地网关）" }) {
            if (apiKey.isNotBlank()) {
                clipboard.setText(AnnotatedString(apiKey))
                scope.launch { snackbar.showSnackbar("已复制 API Key") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "提示：DSH 与网关都在本机，请勿把这些信息发到公网；换设备/重装后 API Key 会变。",
            style = MaterialTheme.typography.labelSmall,
            color = wb.ink3,
        )
        Spacer(Modifier.height(12.dp))
        WbButton("关闭", onClick = onClose, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoCopyRow(label: String, value: String, onCopy: () -> Unit) {
    val wb = LocalWbColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = wb.ink3)
            Mono(value)
        }
        WbButton("复制", onClick = onCopy)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DshWebView(
    manager: DshManager,
    onChromeMajor: (Int) -> Unit = {},
) {
    val wb = LocalWbColors.current
    // DSH 的 token URL 是一次性的，只能加载一次；重复加载会显示
    // "dsh web authentication required"。首次加载换签名 cookie，之后走 base URL。
    val target = remember { manager.webUrl() }
    if (target == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("DSH 控制台地址不可用，请重启内核", color = wb.ink3)
        }
        return
    }

    // 附件按钮：前端用 <input type=file>，WebView 只在 onShowFileChooser 里真正拉起一个
    // 选择器 Activity 时才弹窗；params 为空、Intent 不可启动、上一次回调没释放……任何一步
    // 失败 WebView 都只是静默放弃，前端表现就是「点了没反应」。所以这里逐级兜底并全程
    // 打 logcat（tag WbDshFile / WbDshWeb），把断点定位到具体一层。
    var fileCallback by remember { mutableStateOf<android.webkit.ValueCallback<Array<android.net.Uri>>?>(null) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) {
            return@rememberLauncherForActivityResult
        }
        val uris = if (result.resultCode == android.app.Activity.RESULT_OK) {
            collectPickedUris(result.data)
        } else {
            emptyList()
        }
        Log.i(TAG_FILE, "picker result code=${result.resultCode} uris=${uris.size}")
        runCatching { cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray()) }
    }
    // 页面销毁时不能把 WebView 的 callback 悬着，否则下一次点击永远不会弹选择器。
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val cb = fileCallback
            fileCallback = null
            if (cb != null) runCatching { cb.onReceiveValue(null) }
        }
    }
    // 离开 DSH 页 / 内核重启时主动销毁 WebView：AndroidView 不会替我们释放，
    // 反复进出会累积渲染进程与 JS 堆（内存越用越紧，最终表现为整体卡顿）。
    val webHolder = remember { WebViewHolder() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val v = webHolder.view
            webHolder.view = null
            if (v != null) {
                runCatching { v.stopLoading() }
                runCatching { (v.parent as? android.view.ViewGroup)?.removeView(v) }
                runCatching { v.destroy() }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                webHolder.view = this
                // 不透明底色：透明 WebView 会让整屏每帧多一次 alpha 混合，是滚动掉帧的常见来源。
                setBackgroundColor(wb.bg.toArgb())
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    textZoom = 100
                    // 预栅格化屏幕外内容：长会话滚动时少一截合成开销。
                    offscreenPreRaster = true
                    mediaPlaybackRequiresUserGesture = true
                    javaScriptCanOpenWindowsAutomatically = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        // 只拦截明确的外链协议；http/https/about/data/blob/javascript 一律交给
                        // WebView 自己处理——前端按钮常用 javascript:/data: 触发，拦掉会「点了没反应」。
                        val scheme = request.url.scheme?.lowercase() ?: return false
                        return scheme !in setOf(
                            "http", "https", "about", "data", "blob", "javascript", "file", "chrome",
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.i(TAG_WEB, "pageFinished url=$url title=${view?.title}")
                        manager.markTokenConsumed()
                        injectViewportFix(view)
                        runCatching { view?.evaluateJavascript(FILE_PROBE_JS, null) }
                        runCatching { view?.evaluateJavascript(BG_JS, null) }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        Log.w(TAG_WEB, "httpError ${errorResponse?.statusCode} ${request?.url}")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG_WEB, "mainFrameError ${error?.errorCode} ${error?.description} ${request.url}")
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    private var lastLogAt = 0L

                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        // 流式渲染时前端 console 输出非常密集；release 只留告警/错误并统一限流，
                        // 避免每条日志都写一次 logcat 拖慢渲染线程（debug 包保留全量便于排查）。
                        val level = msg.messageLevel()
                        val important = level == ConsoleMessage.MessageLevel.ERROR ||
                            level == ConsoleMessage.MessageLevel.WARNING
                        if (!com.workbuddy2api.panel.BuildConfig.DEBUG && !important) return true
                        val now = SystemClock.uptimeMillis()
                        if (now - lastLogAt < CONSOLE_LOG_MIN_INTERVAL_MS) return true
                        lastLogAt = now
                        Log.i(TAG_WEB, "console[$level] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                        return true
                    }

                    override fun onShowFileChooser(
                        view: WebView?,
                        callback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                        params: WebChromeClient.FileChooserParams?,
                    ): Boolean {
                        Log.i(
                            TAG_FILE,
                            "onShowFileChooser mode=${params?.mode} accept=${params?.acceptTypes?.joinToString(",") ?: "-"}",
                        )
                        // 释放上一次没走完的请求，否则 WebView 会一直等它。
                        fileCallback?.let { runCatching { it.onReceiveValue(null) } }
                        fileCallback = null
                        if (callback == null) {
                            Log.w(TAG_FILE, "onShowFileChooser: null callback")
                            return false
                        }
                        fileCallback = callback
                        var launched = false
                        for (intent in fileChooserIntents(params)) {
                            val ok = runCatching { filePicker.launch(intent) }
                                .onFailure { Log.w(TAG_FILE, "launch failed: ${intent.action}", it) }
                                .isSuccess
                            Log.i(TAG_FILE, "launch ${intent.action} -> $ok")
                            if (ok) {
                                launched = true
                                break
                            }
                        }
                        if (!launched) {
                            // 一个都启动不了：立刻把 callback 还回去，避免整条链路卡死。
                            fileCallback = null
                            runCatching { callback.onReceiveValue(null) }
                        }
                        return launched
                    }
                }
                if (com.workbuddy2api.panel.BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                // WebView 兼容自检：把 Chromium 主版本回传给 UI
                runCatching {
                    onChromeMajor(
                        com.workbuddy2api.panel.util.DeviceEnv.chromeMajor(settings.userAgentString),
                    )
                }
                // 页面 → 壳：底色上报。直接改 WebView 背景与系统栏图标，不再回传 Compose
                // 状态——主题切换只影响系统栏，不会触发整屏重组与 WebView 重新布局。
                runCatching {
                    val activity = ctx.findActivity()
                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun onBg(css: String) {
                                val color = parseCssColor(css) ?: return
                                // JS 桥跑在 WebView 的 JavaBridge 线程，View/Window 操作必须回 UI 线程。
                                post {
                                    runCatching { setBackgroundColor(color) }
                                    runCatching { applySystemBarAppearance(activity, color) }
                                }
                            }
                        },
                        "wbshell",
                    )
                }
                // 等 WebView 真正有尺寸后再加载，避免 0x0 时初始化渲染视口。
                var loaded = false
                addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: android.view.View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        if (!loaded && v.width > 0 && v.height > 0) {
                            loaded = true
                            v.removeOnLayoutChangeListener(this)
                            (v as? WebView)?.loadUrl(target)
                        }
                    }
                })
                post {
                    if (!loaded && width > 0 && height > 0) {
                        loaded = true
                        loadUrl(target)
                    }
                }
            }
        },
    )
}

private const val TAG_WEB = "WbDshWeb"

/** WebView 持有者：只用于离开页面时销毁实例，不参与 Compose 重组。 */
private class WebViewHolder {
    var view: WebView? = null
}

/** 附件选择器链路的 logcat tag（与页面 bridge 日志分开，便于一眼定位断点）。 */
private const val TAG_FILE = "WbDshFile"

/**
 * 从系统文件选择器返回的 Intent 里取出全部 URI。
 *
 * 多选时系统只把结果放进 Intent.getClipData，data.data 为 null；旧实现只读 data.data，
 * 于是「一次选多个文件」会退化成「什么都没选」。单选时两者可能同时存在，这里按顺序去重。
 */
private fun collectPickedUris(data: android.content.Intent?): List<android.net.Uri> {
    if (data == null) return emptyList()
    val out = ArrayList<android.net.Uri>()
    val clip = data.clipData
    if (clip != null) {
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i)?.uri ?: continue
            if (!out.contains(uri)) out.add(uri)
        }
    }
    val single = data.data
    if (single != null && !out.contains(single)) out.add(single)
    return out
}

/**
 * 逐级兜底的文件选择 Intent。DSH 前端的 input 没写 accept、又开了 multiple，
 * 只有允许具体 MIME 与多选的实现才和 WebView 期望一致。
 *
 * 1. WebView 依据 input 属性生成的 intent（最贴合前端语义）；
 * 2. Intent.ACTION_OPEN_DOCUMENT（SAF，现代 ROM 一定有）；
 * 3. Intent.ACTION_GET_CONTENT（老机型/定制 ROM 常见的实现）。
 *
 * 部分定制 ROM 删掉了其中一种 DocumentsUI，只有前一个不可启动时才会落到后一个。
 */
private fun fileChooserIntents(params: WebChromeClient.FileChooserParams?): List<android.content.Intent> {
    val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
    val accepts = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
    val primary = accepts.firstOrNull { it.contains('/') && it != "*/*" } ?: "*/*"
    val out = ArrayList<android.content.Intent>()
    runCatching { params?.createIntent() }.getOrNull()?.let { given ->
        given.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        out.add(given)
    }
    for (action in listOf(
        android.content.Intent.ACTION_OPEN_DOCUMENT,
        android.content.Intent.ACTION_GET_CONTENT,
    )) {
        out.add(android.content.Intent(action).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = primary
            if (multiple) putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (accepts.isNotEmpty()) putExtra(android.content.Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
        })
    }
    return out
}

/**
 * 附件按钮探针 + file input 可见性兜底。
 *
 * 诊断目的：把「前端根本没点 input[type=file]」和「前端点了、但 WebView 没把
 * onShowFileChooser 交给我们」两种情况分开——前者 logcat 里没有 file input click，
 * 后者有。所有输出都走 console.log，由 onConsoleMessage 落到 tag WbDshWeb。
 *
 * 兼容目的：React 用 hidden 属性渲染这个 input（display:none）。个别 ROM 的 Blink
 * 实现会忽略对 display:none 输入框的程序化 click()，这里改成 1px 不可见块，语义不变。
 */
private const val FILE_PROBE_JS = """
(function(){
  if (window.__wbFileProbe) return;
  window.__wbFileProbe = 1;
  try {
    var st = document.createElement('style');
    st.id = 'wb-file-input';
    st.textContent = 'input[type=file][hidden]{display:block!important;position:fixed!important;'
      + 'left:-10000px!important;top:0!important;width:1px!important;height:1px!important;'
      + 'opacity:0!important;pointer-events:none!important}';
    (document.head || document.documentElement).appendChild(st);
  } catch (e) {}
  try {
    document.addEventListener('click', function(e){
      var t = e.target;
      if (!t || t.tagName !== 'INPUT' || t.type !== 'file') return;
      var d = '';
      try { d = getComputedStyle(t).display; } catch (x) {}
      console.log('[wb] file input click trusted=' + e.isTrusted
        + ' display=' + d + ' disabled=' + t.disabled
        + ' multiple=' + t.multiple + ' accept=' + (t.accept || '-'));
    }, true);
  } catch (e) {}
})();
"""

/**
 * 该机型 WebView（Chromium 149）里 `vh/dvh` 恒为 0（连 about:blank 都如此），
 * DSH 前端依赖 `html{height:100%}` 链，整页高度塌陷为 0，按钮命中区跑到视口外，
 * 表现为「WebView 里点了没反应」。
 *
 * 修法：页面加载后把 html/body/#root 高度钉成实际像素，并把样式表里的 vh 单位就地
 * 换算为 px。
 *
 * 性能约束（重要）：旧实现在 documentElement 上挂了 `childList+subtree` 的
 * MutationObserver，聊天流式输出时每个 token 都会重置定时器，随后全量遍历所有
 * 样式表 + 所有 `[style]` 元素做 `setProperty`——相当于每 350ms 强制一次整页样式
 * 重算，是控制台滚动/打字掉帧的主因。现在：
 *   1. 高度只在视口尺寸真的变化时更新（ResizeObserver + 高度去重）；
 *   2. 全量样式换算只在首帧做一次；
 *   3. 只监听 <head> 里新增的 <style>/<link>，并且只补丁新增节点。
 */
private const val VH_FIX_JS = """
(function(){
  if (window.__wbVhFix) { try { window.__wbVhFix(); } catch(e){} return; }
  var H = 0, patchedOnce = false, scheduled = false;
  var re = /(-?[0-9]*\.?[0-9]+)(?:d|s|l)?vh/g;
  function fixVal(v){ return v.replace(re, function(m, n){ return (parseFloat(n) * H / 100).toFixed(2) + 'px'; }); }
  function patchStyle(st){
    if (!st || !st.length) return;
    for (var k = 0; k < st.length; k++) {
      var prop = st[k], val = st.getPropertyValue(prop);
      if (val && val.indexOf('vh') >= 0) st.setProperty(prop, fixVal(val), st.getPropertyPriority(prop));
    }
  }
  function patchRules(list){
    if (!list) return;
    for (var i = 0; i < list.length; i++) {
      var r = list[i];
      if (r.style && r.style.length) patchStyle(r.style);
      if (r.cssRules && r.cssRules.length) patchRules(r.cssRules);
    }
  }
  function patchInline(){
    var els = document.querySelectorAll('[style]');
    for (var i = 0; i < els.length; i++) patchStyle(els[i].style);
  }
  function patchAll(){
    if (patchedOnce) return;
    patchedOnce = true;
    for (var s = 0; s < document.styleSheets.length; s++) {
      var rules = null;
      try { rules = document.styleSheets[s].cssRules; } catch (e) { continue; }
      if (rules) patchRules(rules);
    }
    try { patchInline(); } catch (e) {}
  }
  function apply(){
    var h = document.documentElement.clientHeight || window.innerHeight || 0;
    if (!h) return;
    var changed = Math.abs(h - H) >= 1;
    H = h;
    var st = document.getElementById('wb-vhfix');
    if (!st) {
      st = document.createElement('style');
      st.id = 'wb-vhfix';
      (document.head || document.documentElement).appendChild(st);
      changed = true;
    }
    if (changed) {
      st.textContent = 'html,body,#root{height:' + H + 'px !important;min-height:' + H + 'px !important}'
                     + 'html,body{margin:0 !important;overflow:hidden !important}';
      patchAll();
    }
  }
  window.__wbVhFix = function(){
    if (scheduled) return;
    scheduled = true;
    var run = function(){ scheduled = false; try { apply(); } catch(e) {} };
    if (window.requestAnimationFrame) requestAnimationFrame(run); else setTimeout(run, 16);
  };
  window.__wbVhFix();
  // 只跟随视口尺寸变化（转屏/全屏切换/键盘），不再监听整棵 DOM。
  try {
    new ResizeObserver(function(){ window.__wbVhFix(); }).observe(document.documentElement);
  } catch (e) {
    window.addEventListener('resize', function(){ window.__wbVhFix(); }, { passive: true });
  }
  window.addEventListener('orientationchange', function(){ setTimeout(function(){ window.__wbVhFix(); }, 260); }, { passive: true });
  // SPA 运行时新增的样式表：只补丁新增的那一个节点。
  function patchLater(node){
    setTimeout(function(){
      try { if (node.sheet) patchRules(node.sheet.cssRules); } catch (e) {}
    }, 300);
  }
  try {
    new MutationObserver(function(muts){
      for (var i = 0; i < muts.length; i++) {
        var nodes = muts[i].addedNodes;
        for (var j = 0; j < nodes.length; j++) {
          var n = nodes[j];
          if (!n || n.nodeType !== 1) continue;
          if (n.tagName === 'STYLE') {
            try { if (n.sheet) patchRules(n.sheet.cssRules); } catch (e) {}
          } else if (n.tagName === 'LINK') {
            patchLater(n);
          }
        }
      }
    }).observe(document.head || document.documentElement, { childList: true });
  } catch (e) {}
})();
"""

/** 在 WebView 里执行视口单位修复（页面加载后立即执行一次，1.2s 后再兜底一次）。 */
private fun injectViewportFix(view: WebView?) {
    if (view == null) return
    runCatching {
        view.evaluateJavascript(VH_FIX_JS, null)
        view.postDelayed({ runCatching { view.evaluateJavascript("window.__wbVhFix&&window.__wbVhFix()", null) } }, 1200)
    }
}
/** WebView 页面底色观察：把 body/documentElement 的实际背景色回传给壳。 */
private const val BG_JS = """
(function(){
  if (window.__wbBg) return;
  window.__wbBg = 1;
  function opaque(s){ return !!s && !/rgba?\([^)]*,\s*0\s*\)/.test(s); }
  function cur(){
    var b = ''; try { b = getComputedStyle(document.body).backgroundColor || ''; } catch(e) {}
    if (opaque(b)) return b;
    var h = ''; try { h = getComputedStyle(document.documentElement).backgroundColor || ''; } catch(e) {}
    return opaque(h) ? h : b;
  }
  function push(){ try { if (window.wbshell && wbshell.onBg) wbshell.onBg(cur()); } catch(e) {} }
  var t = null;
  function soon(){ if (t) return; t = setTimeout(function(){ t = null; push(); }, 250); }
  try {
    new MutationObserver(soon).observe(document.documentElement, {
      attributes: true, attributeFilter: ['class', 'style', 'data-theme', 'color-scheme'],
    });
  } catch(e) {}
  document.addEventListener('transitionend', soon, true);
  push(); setTimeout(push, 800); setTimeout(push, 2500);
})();
"""

/** 解析 `rgb()/rgba()/#rrggbb` 形式的 CSS 颜色。 */
private fun parseCssColor(css: String?): Int? {
    if (css.isNullOrBlank()) return null
    val s = css.trim().lowercase()
    if (s.startsWith("#")) {
        val hex = s.removePrefix("#")
        return when (hex.length) {
            6 -> runCatching { (0xFF000000 or hex.toLong(16)).toInt() }.getOrNull()
            8 -> runCatching { hex.toLong(16).toInt() }.getOrNull()
            3 -> runCatching {
                val r = hex[0].toString().repeat(2)
                val g = hex[1].toString().repeat(2)
                val b = hex[2].toString().repeat(2)
                (0xFF000000 or ("$r$g$b").toLong(16)).toInt()
            }.getOrNull()
            else -> null
        }
    }
    val m = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(s) ?: return null
    val (r, g, b) = m.destructured
    return android.graphics.Color.rgb(r.toInt(), g.toInt(), b.toInt())
}

/** Chromium 版本过低时的提示（DSH 前端要求 80+）。 */
@Composable
private fun DshChromeCompatBanner(chromeMajor: Int) {
    val wb = LocalWbColors.current
    if (chromeMajor <= 0 || chromeMajor >= com.workbuddy2api.panel.util.DeviceEnv.MIN_CHROME_FOR_DSH) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "WebView 内核 Chromium $chromeMajor 偏旧（建议 80+），控制台可能显示异常；" +
                "已自动启用视口修复。",
            style = MaterialTheme.typography.labelSmall,
            color = wb.warn,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 沉浸式全屏：进入 DSH 时隐藏系统状态栏与手势条（上滑临时唤出），
 * 退出回到 App 界面时恢复。布尔值取反即恢复，无需额外状态。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ImmersiveSystemBars(enabled: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    val controller = remember(window, view) {
        window?.let { WindowCompat.getInsetsController(it, view) }
    }
    fun hideBars() {
        runCatching {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(enabled, controller) {
        if (enabled) hideBars() else runCatching { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        onDispose {
            // 离开 DSH 页（切 Tab / 关页）一定要把系统栏还回去，否则主界面会缺状态栏。
            runCatching { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    // 输入法弹出/收起后系统可能把状态栏一起放回来：键盘收起时再隐藏一次。
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(enabled, imeVisible) {
        if (enabled && !imeVisible) hideBars()
    }
}

/**
 * 全屏模式下的浮动「退出全屏」按钮：顶部居中，不挡 DSH 页自身的侧栏/设置入口；
 * 点一下回到 App 界面（底部导航 + 顶栏恢复）。
 */
@Composable
private fun DshExitFullscreenChip(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val wb = LocalWbColors.current
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(top = 8.dp)
            .height(34.dp)
            .clip(shape)
            .clickable(onClick = onExit),
        shape = shape,
        color = if (wb.isDark) wb.surface2 else wb.surface,
        contentColor = wb.ink2,
        border = BorderStroke(1.dp, wb.line),
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FullscreenExit,
                contentDescription = null,
                modifier = Modifier.height(16.dp),
            )
            Text("退出全屏", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 系统栏图标明暗跟随页面底色（页面主题切换时由 JS 桥直接调用，不走重组）。 */
private fun applySystemBarAppearance(activity: Activity?, color: Int) {
    val window = activity?.window ?: return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    val light = isLightColor(color)
    controller.isAppearanceLightStatusBars = light
    controller.isAppearanceLightNavigationBars = light
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** release 包 console 日志的最小间隔：流式输出时避免每条日志都写一次 logcat。 */
private const val CONSOLE_LOG_MIN_INTERVAL_MS = 300L

private fun isLightColor(color: Int): Boolean {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val y = (0.299 * r + 0.587 * g + 0.114 * b)
    return y > 160
}