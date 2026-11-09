package com.workbuddy2api.panel.vdisplay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.workbuddy2api.panel.util.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 安卓虚拟屏（off-screen overlay display）。
 *
 * 系统里多出一块**不对应任何物理屏幕**的显示（开发者选项里的「模拟辅助显示」同一机制），
 * App 可以启动到这块屏上，Agent 通过 shell 注入触摸/文字，再用 screencap 取画面 ——
 * 用户在真机上该干嘛干嘛，两边互不影响。
 *
 * 实现基于 `settings put global overlay_display_devices`，必须由 uid 2000（Shizuku）
 * 写入；后续 `am start --display` / `input -d` / `screencap -d` 同样走 Shizuku。
 * 因此 Android 10 以下、未安装/未授权 Shizuku 时该能力不可用，调用方要给出可读提示。
 *
 * 本对象只做能力封装，不持有 Activity；状态用 [state] 暴露给 UI，桥接层直接 await 挂起函数。
 */
object VirtualScreen {

    const val MIN_API = 29

    data class Info(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val spec: String,
    )

    /** 一张虚拟屏截图；[path] 是应用缓存里的 latest.png（内核同 uid，可直接读）。 */
    data class Shot(val path: String, val width: Int, val height: Int)

    data class AppEntry(val packageName: String, val label: String)

    sealed interface State {
        data object Unsupported : State
        data object Off : State
        data class Starting(val message: String) : State
        data class On(val info: Info) : State
        data class Failed(val message: String) : State
    }

    private const val PREFS = "wb_vdisplay"
    private const val KEY_SPEC = "spec"
    private const val KEY_PREV = "prev"
    private const val KEY_DISPLAY_ID = "display_id"

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    fun supported(): Boolean = Build.VERSION.SDK_INT >= MIN_API

    fun shizukuAvailable(): Boolean = runCatching { ShizukuShell.available() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching { ShizukuShell.granted() }.getOrDefault(false)

    fun ready(): Boolean = supported() && shizukuGranted()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前是否正在运行，并顺带把状态同步到 [state]（UI 回前台、桥接查询时调用）。 */
    fun refresh(context: Context): State {
        if (!supported()) return publish(State.Unsupported)
        if (_state.value is State.Starting) return _state.value
        val info = findOverlay(context)
        return publish(if (info != null) State.On(info) else State.Off)
    }

    fun info(context: Context): Info? =
        (state.value as? State.On)?.info ?: findOverlay(context)

    /** 当前系统里可见的 overlay 显示；优先用上次记录过的 displayId，其次按名字匹配。 */
    private fun findOverlay(context: Context): Info? {
        val p = prefs(context)
        val dm = context.getSystemService(DisplayManager::class.java)
        val displays: List<Display> =
            dm?.let { runCatching { it.displays.toList() }.getOrNull() } ?: emptyList()
        val storedId = p.getInt(KEY_DISPLAY_ID, -1)
        val byId = if (storedId > 0) displays.firstOrNull { it.displayId == storedId } else null
        val byName = displays.firstOrNull { it.name?.contains("Overlay", ignoreCase = true) == true }
        val display = byId ?: byName ?: return cachedInfo(p)
        val spec = p.getString(KEY_SPEC, "").orEmpty()
        return infoOf(display, spec)
    }

    /**
     * 部分 ROM 不把 overlay 显示暴露给普通应用时，用上次创建成功时记录的分辨率兜底。
     * 真正的可用性由截图/输入命令的返回值体现（失败会提示用户关闭重开）。
     */
    private fun cachedInfo(p: android.content.SharedPreferences): Info? {
        val spec = p.getString(KEY_SPEC, null)?.takeIf { it.isNotBlank() } ?: return null
        val id = p.getInt(KEY_DISPLAY_ID, -1)
        if (id <= 0) return null
        val match = Regex("""(\d+)x(\d+)/(\d+)""").find(spec) ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: return null
        val h = match.groupValues[2].toIntOrNull() ?: return null
        val dpi = match.groupValues[3].toIntOrNull() ?: 320
        return Info(id, "Overlay #$id", w, h, dpi, spec)
    }

    private fun infoOf(display: Display, spec: String): Info {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi
        return Info(
            displayId = display.displayId,
            name = display.name ?: "Overlay #?",
            width = w,
            height = h,
            densityDpi = dpi,
            spec = spec.ifBlank { "${w}x${h}/$dpi" },
        )
    }

    // ── 生命周期 ──────────────────────────────────────────────────────

    /**
     * 创建（或按新尺寸重建）虚拟屏。
     *
     * [width]/[height]/[dpi] 是这块虚拟屏的逻辑分辨率；分辨率越接近真机，应用兼容性越好。
     */
    suspend fun start(
        context: Context,
        width: Int = 720,
        height: Int = 1280,
        dpi: Int = 320,
    ): State = mutex.withLock {
        if (!supported()) return@withLock publish(State.Unsupported)
        if (!shizukuGranted()) {
            return@withLock publish(
                State.Failed(
                    "虚拟屏需要 Shizuku 授权（创建系统级显示、注入触摸都要求 uid 2000）。" +
                        "请先安装 Shizuku、启动服务，并在「设置 → 扩展能力」里授权。",
                ),
            )
        }
        publish(State.Starting("正在创建虚拟屏…"))
        val result = try {
            doStart(context, width, height, dpi)
        } catch (e: Exception) {
            State.Failed(e.message?.takeIf { it.isNotBlank() } ?: "创建虚拟屏失败（${e.javaClass.simpleName}）")
        }
        publish(result)
    }

    private suspend fun doStart(context: Context, width: Int, height: Int, dpi: Int): State {
        val p = prefs(context)
        // 记录用户原本的「模拟辅助显示」设置，关闭时原样恢复，避免破坏开发者选项。
        if (!p.contains(KEY_PREV)) {
            val prev = runCatching {
                ShizukuShell.exec("settings get global overlay_display_devices", 8_000).output.trim()
            }.getOrDefault("null")
            p.edit().putString(KEY_PREV, prev.ifBlank { "null" }).apply()
        }
        val safeW = width.coerceIn(240, 2560)
        val safeH = height.coerceIn(240, 2560)
        val safeDpi = dpi.coerceIn(120, 640)
        val spec = "${safeW}x${safeH}/${safeDpi}"
        val r = ShizukuShell.exec("settings put global overlay_display_devices $spec", 12_000)
        if (r.code != 0) {
            return State.Failed("写入系统设置失败（退出码 ${r.code}）：${r.output.take(300)}")
        }
        // 清掉旧 displayId：重建后 ID 可能变化，留着会让 awaitOverlay 立刻返回旧显示的缓存。
        p.edit().putString(KEY_SPEC, spec).remove(KEY_DISPLAY_ID).apply()

        val info = awaitOverlay(context, safeW, safeH, spec)
            ?: return State.Failed(
                "系统没有报告 Overlay 显示器。\n" +
                    runCatching {
                        ShizukuShell.exec("dumpsys display | grep -i -E 'overlay|displayId' | head -n 16", 10_000).output
                    }.getOrDefault("").take(800),
            )
        p.edit().putInt(KEY_DISPLAY_ID, info.displayId).apply()
        return State.On(info)
    }

    /** 轮询 DisplayManager；ROM 不暴露 overlay 显示时用截图反查 displayId 兜底。 */
    private suspend fun awaitOverlay(context: Context, width: Int, height: Int, spec: String): Info? {
        repeat(16) {
            delay(250)
            findOverlay(context)?.let { return it }
        }
        return probeByScreenshot(context, width, height, spec)
    }

    /**
     * 兜底：逐个尝试 displayId 1..8，`screencap -p -d N` 能拿到与目标分辨率一致的 PNG 即认定。
     * 只在 DisplayManager 看不到 overlay（部分定制 ROM）时才会走到这里。
     */
    private suspend fun probeByScreenshot(context: Context, width: Int, height: Int, spec: String): Info? {
        for (id in 1..8) {
            val bytes = runCatching { ShizukuShell.execBytes("screencap -d $id -p", 8_000) }.getOrNull() ?: continue
            val size = pngSize(bytes) ?: continue
            if (size.first != width || size.second != height) continue
            prefs(context).edit().putInt(KEY_DISPLAY_ID, id).apply()
            return Info(id, "Overlay #$id", size.first, size.second, dpiOf(spec), spec)
        }
        return null
    }

    /**
     * 关闭虚拟屏：把我们改过的系统设置恢复成创建前的值。
     * 只处理本应用创建的虚拟屏（没有记录过 spec 时不动系统设置）。
     */
    suspend fun stop(context: Context): State = mutex.withLock {
        if (!shizukuGranted()) {
            return@withLock publish(State.Failed("Shizuku 不可用，无法恢复系统显示设置；请启动 Shizuku 后重试"))
        }
        val p = prefs(context)
        val owned = p.contains(KEY_SPEC) || p.contains(KEY_PREV)
        if (!owned) {
            p.edit().remove(KEY_DISPLAY_ID).apply()
            return@withLock publish(State.Off)
        }
        val prev = p.getString(KEY_PREV, null)
        val command = if (prev.isNullOrBlank() || prev == "null") {
            "settings delete global overlay_display_devices"
        } else {
            "settings put global overlay_display_devices '$prev'"
        }
        val r = runCatching { ShizukuShell.exec(command, 12_000) }.getOrNull()
        if (r == null || r.code != 0) {
            return@withLock publish(
                State.Failed("恢复系统设置失败：${r?.output?.take(300) ?: "Shizuku 执行异常"}"),
            )
        }
        p.edit().remove(KEY_PREV).remove(KEY_SPEC).remove(KEY_DISPLAY_ID).apply()
        publish(State.Off)
    }

    // ── 截图 ──────────────────────────────────────────────────────────

    /**
     * 截取虚拟屏当前画面（PNG 字节）。
     *
     * [persist] 为 true 时写入 `cache/vdisplay/latest.png` 供 Agent 读取；
     * UI 预览每 1.5 秒取一帧，走 false，避免持续写闪存。
     */
    suspend fun capture(context: Context, persist: Boolean = false): ByteArray? = withContext(Dispatchers.IO) {
        val info = info(context) ?: return@withContext null
        if (!shizukuGranted()) return@withContext null
        val bytes = runCatching {
            ShizukuShell.execBytes("screencap -d ${info.displayId} -p", 20_000)
        }.getOrNull() ?: return@withContext null
        if (pngSize(bytes) == null) return@withContext null
        if (persist) {
            runCatching {
                val dir = File(context.cacheDir, "vdisplay").apply { mkdirs() }
                File(dir, "latest.png").writeBytes(bytes)
            }
        }
        bytes
    }

    fun latestShotPath(context: Context): String =
        File(File(context.cacheDir, "vdisplay"), "latest.png").absolutePath

    suspend fun shot(context: Context): Shot? {
        val bytes = capture(context, persist = true) ?: return null
        val size = pngSize(bytes) ?: return null
        return Shot(latestShotPath(context), size.first, size.second)
    }

    // ── 输入注入 ──────────────────────────────────────────────────────

    suspend fun tap(context: Context, x: Int, y: Int): Result<Unit> =
        input(context, "tap ${x.coerceAtLeast(0)} ${y.coerceAtLeast(0)}")

    suspend fun swipe(
        context: Context,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 300,
    ): Result<Unit> =
        input(
            context,
            "swipe ${x1.coerceAtLeast(0)} ${y1.coerceAtLeast(0)} " +
                "${x2.coerceAtLeast(0)} ${y2.coerceAtLeast(0)} ${durationMs.coerceIn(50, 3000)}",
        )

    suspend fun key(context: Context, code: Int): Result<Unit> = input(context, "keyevent $code")

    /**
     * 输入文字。
     *
     * `input text` 只对 ASCII 可靠（KeyCharacterMap 映射不出来 CJK）；中文/emoji 走
     * 「写系统剪贴板 + KEYCODE_PASTE(279)」的兜底，能覆盖多数输入框（个别 App 会拦截粘贴）。
     */
    suspend fun text(context: Context, value: String): Result<Unit> {
        if (value.isEmpty()) return Result.failure(IllegalArgumentException("text 为空"))
        val info = info(context) ?: return Result.failure(IllegalStateException("虚拟屏未开启"))
        val ascii = value.all { it.code in 0x20..0x7E }
        if (ascii) {
            val escaped = value.replace(" ", "%s").replace("'", "'\\''")
            return input(context, "text '$escaped'")
        }
        val copied = withContext(Dispatchers.Main) {
            runCatching {
                val cm = context.getSystemService(ClipboardManager::class.java)
                    ?: return@withContext false
                cm.setPrimaryClip(ClipData.newPlainText("WorkBuddy 虚拟屏", value))
                true
            }.getOrDefault(false)
        }
        if (!copied) return Result.failure(IllegalStateException("写入剪贴板失败，无法输入非 ASCII 文字"))
        val r = runCatching {
            ShizukuShell.exec("input -d ${info.displayId} keyevent 279", 12_000)
        }.getOrNull() ?: return Result.failure(IllegalStateException("Shizuku 执行失败"))
        return if (r.code == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("粘贴失败（退出码 ${r.code}）：${r.output.take(200)}"))
        }
    }

    private suspend fun input(context: Context, args: String): Result<Unit> {
        val info = info(context) ?: return Result.failure(IllegalStateException("虚拟屏未开启"))
        return inputOn(info, args)
    }

    private suspend fun inputOn(info: Info, args: String): Result<Unit> {
        if (!shizukuGranted()) return Result.failure(IllegalStateException("Shizuku 未授权"))
        val r = runCatching {
            ShizukuShell.exec("input -d ${info.displayId} $args", 12_000)
        }.getOrNull() ?: return Result.failure(IllegalStateException("Shizuku 执行失败"))
        return if (r.code == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("输入注入失败（退出码 ${r.code}）：${r.output.take(200)}"))
        }
    }

    // ── 应用列表与启动 ────────────────────────────────────────────────

    fun apps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val self = context.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == self) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            AppEntry(pkg, label)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    suspend fun launch(context: Context, packageName: String): Result<String> {
        if (packageName == context.packageName) {
            return Result.failure(IllegalStateException("不要把 WorkBuddy 自己启动到虚拟屏（会导致两个控制台实例）"))
        }
        val info = info(context) ?: return Result.failure(IllegalStateException("虚拟屏未开启"))
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val activity = runCatching { pm.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
            .firstOrNull()
            ?.activityInfo
            ?: return Result.failure(IllegalStateException("找不到 $packageName 的启动入口（未安装或没有桌面图标）"))
        val component = "${activity.packageName}/${activity.name}"
        val r = runCatching {
            ShizukuShell.exec("am start --display ${info.displayId} -n '$component'", 25_000)
        }.getOrNull() ?: return Result.failure(IllegalStateException("Shizuku 执行失败"))
        val out = r.output
        val failed = r.code != 0 || out.contains("Error") || out.contains("Exception") || out.contains("does not exist")
        return if (failed) {
            Result.failure(IllegalStateException("启动失败：${out.take(400)}"))
        } else {
            Result.success("已在虚拟屏启动 $packageName")
        }
    }

    // ── 工具函数 ──────────────────────────────────────────────────────

    private fun publish(next: State): State {
        _state.value = next
        return next
    }

    private fun dpiOf(spec: String): Int =
        Regex("""\d+x\d+/(\d+)""").find(spec)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 320

    /** 读 PNG 的 IHDR 宽高（不依赖 BitmapFactory，纯字节，可在任意线程跑）。 */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        if (bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte() ||
            bytes[2] != 0x4E.toByte() || bytes[3] != 0x47.toByte()
        ) {
            return null
        }
        fun be32(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        val w = be32(16)
        val h = be32(20)
        return if (w > 0 && h > 0) w to h else null
    }
}
