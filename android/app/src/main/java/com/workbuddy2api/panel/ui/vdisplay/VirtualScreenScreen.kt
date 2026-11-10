package com.workbuddy2api.panel.ui.vdisplay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.components.InfoRow
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.SectionHeader
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbTextField
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbDimens
import com.workbuddy2api.panel.ui.theme.WbMono
import com.workbuddy2api.panel.util.ShizukuShell
import com.workbuddy2api.panel.vdisplay.VirtualScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 虚拟屏预设尺寸：手机竖屏两种 + 平板横屏一种。 */
private data class Preset(val label: String, val width: Int, val height: Int, val dpi: Int)

private val PRESETS = listOf(
    Preset("720×1280", 720, 1280, 320),
    Preset("1080×1920", 1080, 1920, 420),
    Preset("1280×800", 1280, 800, 240),
)

/**
 * 虚拟屏控制台：开启/关闭一块 off-screen 显示，预览画面并直接注入触摸/文字。
 *
 * Agent 侧的同一能力由 `wb_vdisplay` / `wb_vscreen` / `wb_vinput`（DSH 插件）与
 * native 助手的 `vdisplay` / `vlaunch` / `vtap` … 工具使用；这一页给用户做手动兜底与观察。
 */
@Composable
fun VirtualScreenScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val wb = LocalWbColors.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by VirtualScreen.state.collectAsStateWithLifecycle()

    var presetIndex by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var shotAt by remember { mutableLongStateOf(0L) }
    var frameTick by remember { mutableIntStateOf(0) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var inputText by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<VirtualScreen.AppEntry>>(emptyList()) }
    var appQuery by remember { mutableStateOf("") }
    var launchError by remember { mutableStateOf("") }
    var shizukuGranted by remember { mutableStateOf(VirtualScreen.shizukuGranted()) }
    var shizukuAvailable by remember { mutableStateOf(VirtualScreen.shizukuAvailable()) }

    val info = (state as? VirtualScreen.State.On)?.info
    val running = info != null
    val preset = PRESETS[presetIndex.coerceIn(PRESETS.indices)]

    suspend fun refreshShot() {
        // 服务会把最新帧写成 cache/vds/frame.png，App 直接读，不用 screencap。
        val bitmap = VirtualScreen.readFrame(context)
        if (bitmap != null) {
            shot = bitmap
            shotAt = VirtualScreen.latestFrameAt()
            frameTick++
        }
    }

    // 回前台时同步真实显示状态（可能被开发者选项/其它工具改过）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { VirtualScreen.validate(context) }
                shizukuAvailable = VirtualScreen.shizukuAvailable()
                shizukuGranted = VirtualScreen.shizukuGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        VirtualScreen.validate(context)
        shizukuAvailable = VirtualScreen.shizukuAvailable()
        shizukuGranted = VirtualScreen.shizukuGranted()
    }

    LaunchedEffect(running) {
        if (!running) {
            shot = null
            apps = emptyList()
            return@LaunchedEffect
        }
        apps = withContext(Dispatchers.IO) { VirtualScreen.apps(context) }
    }

    // 自动刷新只在页面可见时跑，避免后台每 1.5 秒截一次屏。
    LaunchedEffect(auto, running, lifecycleOwner) {
        if (!auto || !running) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                refreshShot()
                delay(1500)
            }
        }
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Column(Modifier.fillMaxSize()) {
        WbTopBar(
            title = "虚拟屏",
            subtitle = if (running) {
                "displayId=${info?.displayId} · ${info?.width}×${info?.height}@${info?.densityDpi}"
            } else {
                "独立于真机的 Android 显示"
            },
            onBack = onBack,
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WbDimens.screenPadding),
        ) {
            Spacer(Modifier.height(6.dp))

            // ── 状态 ────────────────────────────────────────────────
            SectionHeader(title = "虚拟屏", subtitle = "在后台跑 App，不影响你当前操作")
            WbCard {
                val st = state
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (st) {
                                is VirtualScreen.State.Unsupported -> "当前设备不支持"
                                is VirtualScreen.State.Off -> "未开启"
                                is VirtualScreen.State.Starting -> st.message
                                is VirtualScreen.State.On -> "运行中"
                                is VirtualScreen.State.Failed -> "创建失败"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = wb.ink,
                        )
                        Text(
                            text = "shell/root 侧服务创建的 off-screen VirtualDisplay（PNG 帧写回缓存），" +
                                "对用户真实屏幕零占用，也不改写任何系统设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = wb.ink3,
                        )
                    }
                    StatusChip(
                        text = when {
                            !VirtualScreen.supported() -> "不支持"
                            running -> "运行中"
                            st is VirtualScreen.State.Failed -> "失败"
                            else -> "未开启"
                        },
                        tone = when {
                            !VirtualScreen.supported() -> Tone.Neutral
                            running -> Tone.Ok
                            st is VirtualScreen.State.Failed -> Tone.Bad
                            else -> Tone.Neutral
                        },
                    )
                }

                if (!shizukuAvailable || !shizukuGranted) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (!shizukuAvailable) {
                            "虚拟屏由 shell/root 侧的 app_process 服务创建，创建、截图、启动应用、输入注入都依赖 Shizuku。" +
                                "安装并启动 Shizuku 后回到这里授权。"
                        } else {
                            "已检测到 Shizuku，但本应用还没授权。授权后才能开启虚拟屏（创建/截图/启动/输入都走它）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = wb.warn,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (shizukuAvailable) {
                        WbButton("申请 Shizuku 授权", onClick = {
                            runCatching { ShizukuShell.requestPermission() }
                            // Shizuku 的授权弹窗是异步的；结果回到前台时在 ON_RESUME 里刷新。
                            scope.launch {
                                delay(800)
                                shizukuGranted = VirtualScreen.shizukuGranted()
                                toast(if (shizukuGranted) "Shizuku 已授权" else "仍未授权，请到 Shizuku App 里确认")
                            }
                        })
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("分辨率", style = MaterialTheme.typography.labelMedium, color = wb.ink3)
                Spacer(Modifier.height(4.dp))
                FilterChips(
                    labels = PRESETS.map { it.label },
                    selectedIndex = presetIndex.coerceIn(PRESETS.indices),
                    onSelect = { presetIndex = it },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "切换分辨率会重建虚拟屏（里面的 App 会被关掉）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = wb.ink3,
                )

                if (info != null) {
                    Spacer(Modifier.height(10.dp))
                    InfoRow("displayId", info.displayId.toString(), mono = true)
                    InfoRow("分辨率", "${info.width}×${info.height}", mono = true)
                    InfoRow("密度", "${info.densityDpi} dpi", mono = true)
                }

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = wb.bad)
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WbButton(
                        text = when {
                            busy == "start" -> "创建中…"
                            running -> "按当前尺寸重建"
                            else -> "开启虚拟屏"
                        },
                        onClick = {
                            if (busy.isNotEmpty()) return@WbButton
                            busy = "start"
                            error = ""
                            scope.launch {
                                when (val r = VirtualScreen.start(context, preset.width, preset.height, preset.dpi)) {
                                    is VirtualScreen.State.Failed -> error = r.message
                                    is VirtualScreen.State.Unsupported -> error = "需要 Android 8+"
                                    else -> toast("虚拟屏已就绪")
                                }
                                busy = ""
                            }
                        },
                        enabled = busy.isEmpty() && VirtualScreen.supported() && VirtualScreen.shizukuGranted(),
                        primary = true,
                        modifier = Modifier.weight(1f),
                    )
                    WbButton(
                        text = if (busy == "stop") "关闭中…" else "关闭虚拟屏",
                        onClick = {
                            if (busy.isNotEmpty()) return@WbButton
                            busy = "stop"
                            error = ""
                            scope.launch {
                                when (val r = VirtualScreen.stop(context)) {
                                    is VirtualScreen.State.Failed -> error = r.message
                                    else -> toast("已关闭虚拟屏")
                                }
                                busy = ""
                            }
                        },
                        enabled = busy.isEmpty() && (running || state is VirtualScreen.State.Failed),
                        tone = Tone.Bad,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(WbDimens.gap))

            // ── 画面 ────────────────────────────────────────────────
            SectionHeader(
                title = "画面",
                subtitle = if (running) "点画面 = 在虚拟屏对应坐标点一下" else "开启后可预览",
                trailing = {
                    if (running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自动", style = MaterialTheme.typography.labelSmall, color = wb.ink3)
                            Switch(checked = auto, onCheckedChange = { auto = it })
                        }
                    }
                },
            )
            WbCard {
                if (!running) {
                    Text(
                        "虚拟屏未开启。开启后这里会显示实时画面，可直接点按操作（例如先启动微信扫码登录）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = wb.ink3,
                    )
                } else {
                    val bitmap = shot
                    if (bitmap == null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(wb.surface2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "等待首帧…（启动一个 App 后才有画面）",
                                style = MaterialTheme.typography.bodySmall,
                                color = wb.ink3,
                            )
                        }
                    } else {
                        Image(
                            // frameTick 变化会重建 ImageBitmap：底层 Bitmap 是复用的，
                            // 不换包装层的话 Compose 会认为还是同一张图而不重绘。
                            bitmap = remember(frameTick) { bitmap.asImageBitmap() },
                            contentDescription = "虚拟屏画面",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(
                                    (info?.width ?: 1).toFloat() /
                                        (info?.height ?: 1).toFloat(),
                                )
                                .clip(RoundedCornerShape(12.dp))
                                .background(wb.surface2)
                                .onSizeChanged { previewSize = it }
                                .pointerInput(info?.displayId, previewSize) {
                                    detectTapGestures { offset ->
                                        val size = previewSize
                                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                                        val w = info?.width ?: return@detectTapGestures
                                        val h = info?.height ?: return@detectTapGestures
                                        val x = (offset.x / size.width * w).toInt().coerceIn(0, w - 1)
                                        val y = (offset.y / size.height * h).toInt().coerceIn(0, h - 1)
                                        scope.launch {
                                            val r = VirtualScreen.tap(context, x, y)
                                            if (r.isFailure) toast(r.exceptionOrNull()?.message ?: "点击失败")
                                            else refreshShot()
                                        }
                                    }
                                },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (shotAt > 0) {
                                val s = ((System.currentTimeMillis() - shotAt) / 1000).coerceAtLeast(0)
                                "画面 ${shot?.width}×${shot?.height} · ${s}s 前"
                            } else {
                                "尚未截到画面"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = wb.ink3,
                            modifier = Modifier.weight(1f),
                        )
                        WbButton("刷新", onClick = { scope.launch { refreshShot() } })
                    }
                }
            }

            if (running) {
                Spacer(Modifier.height(WbDimens.gap))

                // ── 输入 ────────────────────────────────────────────
                SectionHeader(title = "输入", subtitle = "文字/按键注入到虚拟屏当前焦点")
                WbCard {
                    WbTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = "发送到虚拟屏的文字",
                        placeholder = "例如：你好，帮我看看这个",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WbButton(
                            "发送文字",
                            onClick = {
                                if (inputText.isBlank()) return@WbButton
                                scope.launch {
                                    val r = VirtualScreen.text(context, inputText)
                                    if (r.isFailure) toast(r.exceptionOrNull()?.message ?: "输入失败")
                                    else {
                                        toast("已发送")
                                        inputText = ""
                                        refreshShot()
                                    }
                                }
                            },
                            primary = true,
                            modifier = Modifier.weight(1f),
                        )
                        WbButton("返回", onClick = {
                            scope.launch {
                                VirtualScreen.key(context, 4)
                                refreshShot()
                            }
                        })
                        WbButton("桌面", onClick = {
                            scope.launch {
                                VirtualScreen.key(context, 3)
                                refreshShot()
                            }
                        })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ASCII 走 input text；中文/emoji 走剪贴板粘贴，个别输入框可能不生效。" +
                            "虚拟屏与真机共用应用数据，页面上的登录态/账号和真机一致。",
                        style = MaterialTheme.typography.labelSmall,
                        color = wb.ink3,
                    )
                }

                Spacer(Modifier.height(WbDimens.gap))

                // ── 应用 ────────────────────────────────────────────
                SectionHeader(title = "在虚拟屏启动应用", subtitle = "am start --display，由 shell/root 侧执行")
                WbCard {
                    WbTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        label = "搜索应用",
                        placeholder = "应用名或包名",
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "启动会先结束该 App 在真机上的实例，再在虚拟屏重新打开；微信等拒绝副屏的 App 会提示不支持。",
                        style = MaterialTheme.typography.labelSmall,
                        color = wb.ink3,
                    )
                    Spacer(Modifier.height(6.dp))
                    val filtered = apps
                        .filter {
                            appQuery.isBlank() ||
                                it.label.contains(appQuery, ignoreCase = true) ||
                                it.packageName.contains(appQuery, ignoreCase = true)
                        }
                        .take(30)
                    if (filtered.isEmpty()) {
                        Text(
                            if (apps.isEmpty()) "读取应用列表…" else "没有匹配的应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = wb.ink3,
                        )
                    } else {
                        filtered.forEach { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = wb.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = WbMono,
                                            fontSize = 10.5.sp,
                                        ),
                                        color = wb.ink3,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                WbButton("启动", onClick = {
                                    scope.launch {
                                        val r = VirtualScreen.launch(context, app.packageName)
                                        launchError = r.exceptionOrNull()?.message ?: ""
                                        toast(r.getOrElse { it.message ?: "启动失败" })
                                        delay(600)
                                        refreshShot()
                                    }
                                })
                            }
                        }
                    }
                    if (launchError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            launchError,
                            style = MaterialTheme.typography.bodySmall,
                            color = wb.bad,
                        )
                    }
                }
            }

            Spacer(Modifier.height(WbDimens.gap))

            // ── 说明 ────────────────────────────────────────────────
            SectionHeader(title = "说明")
            WbCard {
                Text(
                    "• 虚拟屏由 Shizuku 侧的 app_process 服务创建并持有，不改写任何系统设置；" +
                        "服务独立于 App 进程，App 退出后虚拟屏仍在，直到你点「关闭虚拟屏」或重启手机。\n" +
                        "• 创建、截图、启动应用、输入注入都需要 Shizuku（普通设备 uid 2000，KernelSU/Magisk 为 root）；" +
                        "Android 8 以下不支持。\n" +
                        "• 部分单实例应用（微信等）在同一时间只允许一个窗口，启动到虚拟屏后真机上的窗口会被移走；" +
                        "需要两边同时用时，优先选支持多窗口的应用。\n" +
                        "• 关屏/深度休眠时虚拟屏可能停止渲染，长任务建议保持充电与不清理后台。\n" +
                        "• Agent 工具：DSH 用 `wb_vdisplay` / `wb_vscreen` / `wb_vinput`；原生助手用 " +
                        "`vdisplay` / `vlaunch` / `vscreen` / `vtap` / `vtype`。",
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink3,
                )
                Spacer(Modifier.height(8.dp))
                Mono("VirtualDisplay #${info?.displayId ?: "-"} · input -d ${info?.displayId ?: "ID"}")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
