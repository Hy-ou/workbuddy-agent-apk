package com.workbuddy2api.panel.ui.dsh

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
fun DshScreen(manager: DshManager, gatewayApiKey: String = "") {
    val wb = LocalWbColors.current
    val scope = rememberCoroutineScope()
    val state by manager.state.collectAsStateWithLifecycle()
    var showLogs by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    // WebView 兼容自检（Chromium 版本）与页面底色（状态栏跟随）
    var chromeMajor by remember { mutableIntStateOf(0) }
    var pageBg by remember { mutableStateOf<Int?>(null) }
    // 进入 DSH 页即自动拉起内核（未运行时），省掉一次点击。
    LaunchedEffect(Unit) {
        val cur = manager.state.value
        if (cur is State.Stopped || cur is State.Failed) {
            manager.startAsync().join()
        }
    }

    Column(Modifier.fillMaxSize()) {
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
        ApplyPageBackgroundToSystemBars(pageBg)
        when (val s = state) {
            is State.Running -> DshWebView(
                manager = manager,
                onChromeMajor = { chromeMajor = it },
                onPageBg = { pageBg = it },
            )
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
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = wb.ink2)
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
                        s.message,
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
    onPageBg: (Int?) -> Unit = {},
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

    // 附件按钮：前端用 <input type=file>，WebView 必须实现 onShowFileChooser 才会弹选择器。
    var fileCallback by remember { mutableStateOf<android.webkit.ValueCallback<Array<android.net.Uri>>?>(null) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) {
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            cb.onReceiveValue(arrayOf(uri))
        } else {
            cb.onReceiveValue(null)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    textZoom = 100
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
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                        Log.i(
                            TAG_WEB,
                            "console[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}",
                        )
                        return true
                    }

                    override fun onShowFileChooser(
                        view: WebView?,
                        callback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                        params: WebChromeClient.FileChooserParams?,
                    ): Boolean {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = callback
                        return try {
                            val intent = params?.createIntent()
                            if (intent == null) {
                                fileCallback = null
                                callback?.onReceiveValue(null)
                                false
                            } else {
                                filePicker.launch(intent)
                                true
                            }
                        } catch (t: Throwable) {
                            fileCallback = null
                            callback?.onReceiveValue(null)
                            false
                        }
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
                // 页面 → 壳：底色上报（状态栏/导航栏跟随 DSH 主题）
                runCatching {
                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun onBg(css: String) {
                                runCatching { onPageBg(parseCssColor(css)) }
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

/**
 * 该机型 WebView（Chromium 149）里 `vh/dvh` 恒为 0（连 about:blank 都如此），
 * DSH 前端依赖 `html{height:100%}` 链，整页高度塌陷为 0，按钮命中区跑到视口外，
 * 表现为「WebView 里点了没反应」。
 *
 * 修法：页面加载后把 html/body/#root 高度钉成实际像素，并把样式表里的 vh 单位就地
 * 换算为 px；SPA 后续动态插入的样式由 MutationObserver 兜底。
 */
private const val VH_FIX_JS = """
(function(){
  if (window.__wbVhFix) { try { window.__wbVhFix(); } catch(e){} return; }
  var H = document.documentElement.clientHeight || window.innerHeight || 0;
  if (!H) return;
  var re = /(-?[0-9]*\.?[0-9]+)(?:d|s|l)?vh/g;
  function fixVal(v){ return v.replace(re, function(m, n){ return (parseFloat(n) * H / 100).toFixed(2) + 'px'; }); }
  function patchStyle(st){
    for (var k = 0; k < st.length; k++) {
      var prop = st[k], val = st.getPropertyValue(prop);
      if (val && val.indexOf('vh') >= 0) st.setProperty(prop, fixVal(val), st.getPropertyPriority(prop));
    }
  }
  function patchRules(list){
    for (var i = 0; i < list.length; i++) {
      var r = list[i];
      if (r.style && r.style.length) patchStyle(r.style);
      if (r.cssRules) patchRules(r.cssRules);
    }
  }
  function patchInline(){
    var els = document.querySelectorAll('[style]');
    for (var i = 0; i < els.length; i++) patchStyle(els[i].style);
  }
  window.__wbVhFix = function(){
    var h = document.documentElement.clientHeight;
    if (!h) return;
    H = h;
    var st = document.getElementById('wb-vhfix');
    if (!st) { st = document.createElement('style'); st.id = 'wb-vhfix'; (document.head || document.documentElement).appendChild(st); }
    st.textContent = 'html,body,#root{height:' + H + 'px !important;min-height:' + H + 'px !important}'
                   + 'html,body{margin:0 !important;overflow:hidden !important}';
    for (var s = 0; s < document.styleSheets.length; s++) {
      var rules = null;
      try { rules = document.styleSheets[s].cssRules; } catch (e) { continue; }
      if (rules) patchRules(rules);
    }
    patchInline();
  };
  window.__wbVhFix();
  try {
    new MutationObserver(function(){
      clearTimeout(window.__wbVhT);
      window.__wbVhT = setTimeout(function(){ try { window.__wbVhFix(); } catch(e){} }, 350);
    }).observe(document.documentElement, { childList: true, subtree: true });
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

/** 让状态栏/导航栏跟随 DSH 页面底色（浅色底用深色图标）。 */
@Composable
private fun ApplyPageBackgroundToSystemBars(bg: Int?) {
    val view = LocalView.current
    LaunchedEffect(bg) {
        val color = bg ?: return@LaunchedEffect
        val activity = view.context as? android.app.Activity ?: return@LaunchedEffect
        val window = activity.window
        val light = isLightColor(color)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (light) {
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    } else {
                        0
                    },
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                )
            } else {
                @Suppress("DEPRECATION")
                window.statusBarColor = color
                @Suppress("DEPRECATION")
                window.navigationBarColor = color
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (light) {
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    0
                }
            }
        }
    }
}

private fun isLightColor(color: Int): Boolean {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val y = (0.299 * r + 0.587 * g + 0.114 * b)
    return y > 160
}