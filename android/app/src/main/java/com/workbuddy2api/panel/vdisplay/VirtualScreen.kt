package com.workbuddy2api.panel.vdisplay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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
 * 安卓虚拟屏（shell/root 侧 app_process 服务版）。
 *
 * 为什么不是 App 自己 `DisplayManager.createVirtualDisplay`：
 * Android 17 的 ActivityTaskSupervisor 明确限制普通 App 在虚拟屏上启动**其他 App**
 * （`SecurityManagerService.isVirtualDisplayActive` 直接拒绝，即使 App 是 display owner）。
 * 因此虚拟屏改由 Shizuku 侧的小服务 `vds.dex` 创建并持有（uid 2000 或 root）：
 *
 *   app_process -Djava.class.path=<files>/vds.dex /system/bin com.wb.vds.Server W H DPI DIR
 *
 * 服务把 displayId 写到 `DIR/id`，把最新帧写到 `DIR/frame.png`；App 只读自己的缓存。
 * 启动 App 时用同一 uid 的 `am start --display <id>`，显示 owner 与调用者一致，
 * 既通过系统的虚拟屏限制，也不会触发 HyperOS 的跨应用「应用跳转」确认。
 *
 * 于是：
 * - 创建/截图：服务完成，App 读文件；
 * - 启动 App：`am start --display`（Shizuku）；
 * - 输入：`input -d`（Shizuku）。
 * 所有能力都依赖 Shizuku（普通设备 uid 2000，KernelSU/Magisk 设备为 root）。
 */
object VirtualScreen {

    /** app_process + VirtualDisplay + am --display：Android 8（API 26）起稳定。 */
    const val MIN_API = 26

    data class Info(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val spec: String,
    )

    data class Shot(val path: String, val width: Int, val height: Int)

    data class AppEntry(val packageName: String, val label: String)

    sealed interface State {
        data object Unsupported : State
        data object Off : State
        data class Starting(val message: String) : State
        data class On(val info: Info) : State
        data class Failed(val message: String) : State
    }

    private const val DEX_ASSET = "vds.dex"
    private const val SERVER_CLASS = "com.wb.vds.Server"

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private val frameLock = Any()

    @Volatile private var current: Info? = null
    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastFrameStamp: Long = -1L
    @Volatile private var lastFrameAt: Long = 0L

    fun supported(): Boolean = Build.VERSION.SDK_INT >= MIN_API

    fun shizukuAvailable(): Boolean = runCatching { ShizukuShell.available() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching { ShizukuShell.granted() }.getOrDefault(false)

    /** 虚拟屏服务与输入注入都依赖 Shizuku。 */
    fun ready(): Boolean = supported() && shizukuGranted()

    fun info(context: Context): Info? = current

    // ── 生命周期 ──────────────────────────────────────────────────────

    suspend fun start(
        context: Context,
        width: Int = 720,
        height: Int = 1280,
        dpi: Int = 320,
    ): State = mutex.withLock {
        if (!supported()) return@withLock publish(State.Unsupported)
        if (!shizukuGranted()) {
            return@withLock publish(
                State.Failed("需要 Shizuku 已授权：虚拟屏由 shell/root 侧的 app_process 创建，创建、截图、启动、输入都走它"),
            )
        }
        publish(State.Starting("正在启动虚拟屏服务…"))
        val result = try {
            doStart(
                context,
                width.coerceIn(240, 2560),
                height.coerceIn(240, 2560),
                dpi.coerceIn(120, 640),
            )
        } catch (e: Throwable) {
            State.Failed(e.message?.takeIf { it.isNotBlank() } ?: "启动虚拟屏失败（${e.javaClass.simpleName}）")
        }
        publish(result)
    }

    private suspend fun doStart(context: Context, width: Int, height: Int, dpi: Int): State {
        killServer()
        val dir = vdsDir(context).apply { mkdirs() }
        clearStateFiles(dir)

        val dex = File(context.filesDir, DEX_ASSET)
        withContext(Dispatchers.IO) {
            context.assets.open(DEX_ASSET).use { input ->
                dex.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!dex.exists() || dex.length() <= 0L) {
            return State.Failed("内置虚拟屏服务缺失（assets/$DEX_ASSET）")
        }

        val cmd = "nohup app_process -Djava.class.path=${dex.absolutePath} /system/bin $SERVER_CLASS " +
            "$width $height $dpi ${dir.absolutePath} > ${dir.absolutePath}/log 2>&1 &"
        val r = runCatching { ShizukuShell.exec(cmd, 12_000) }.getOrNull()
            ?: return State.Failed("Shizuku 执行失败，无法启动虚拟屏服务")

        val idFile = File(dir, "id")
        var id = 0
        for (i in 0 until 80) {
            delay(100)
            id = runCatching { idFile.readText().trim().toInt() }.getOrDefault(0)
            if (id > 0) break
        }
        if (id <= 0) {
            val log = runCatching { File(dir, "log").readText().take(400) }.getOrDefault("")
            val out = r.output.take(200)
            return State.Failed(
                "虚拟屏服务启动失败" +
                    (out.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "") +
                    (log.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""),
            )
        }

        val spec = "$width×$height@$dpi"
        runCatching { File(dir, "meta").writeText("$width,$height,$dpi") }
        val info = Info(id, "WorkBuddy 虚拟屏", width, height, dpi, spec)
        current = info
        return State.On(info)
    }

    /**
     * 关闭虚拟屏：杀掉 shell/root 服务，显示随之释放；不清任何系统设置。
     */
    suspend fun stop(context: Context): State = mutex.withLock {
        publish(State.Starting("正在关闭虚拟屏…"))
        val ok = killServer()
        val dir = vdsDir(context)
        clearStateFiles(dir)
        current = null
        synchronized(frameLock) {
            lastFrame = null
            lastFrameStamp = -1L
            lastFrameAt = 0L
        }
        publish(if (ok) State.Off else State.Failed("关闭失败：Shizuku 不可用，无法结束虚拟屏服务"))
    }

    private suspend fun killServer(): Boolean {
        if (!shizukuGranted()) return false
        val r = runCatching { ShizukuShell.exec("pkill -f $SERVER_CLASS", 8_000) }.getOrNull()
        // pkill 找到进程返回 0；没找到返回 1，同样视为已关闭。
        return r != null
    }

    fun refresh(context: Context): State {
        if (!supported()) return publish(State.Unsupported)
        val dir = vdsDir(context)
        val id = runCatching { File(dir, "id").readText().trim().toInt() }.getOrDefault(0)
        if (id <= 0) {
            current = null
            return publish(State.Off)
        }
        val meta = runCatching {
            File(dir, "meta").readText().split(",").map { it.trim().toInt() }
        }.getOrNull()
        val w = meta?.getOrNull(0) ?: 720
        val h = meta?.getOrNull(1) ?: 1280
        val dpi = meta?.getOrNull(2) ?: 320
        val info = Info(id, "WorkBuddy 虚拟屏", w, h, dpi, "$w×$h@$dpi")
        current = info
        return publish(State.On(info))
    }

    /**
     * 校验虚拟屏服务是否还活着：读 pid 后 `kill -0`，服务已死则清掉残留 id/frame，
     * 避免 App 重启/服务被杀后仍显示「运行中」。
     */
    suspend fun validate(context: Context): State = mutex.withLock {
        if (!supported()) return@withLock publish(State.Unsupported)
        val dir = vdsDir(context)
        val id = runCatching { File(dir, "id").readText().trim().toInt() }.getOrDefault(0)
        if (id <= 0) {
            current = null
            return@withLock publish(State.Off)
        }
        val pid = runCatching { File(dir, "pid").readText().trim().toInt() }.getOrDefault(0)
        // 新版本服务一定会写 pid；没有 pid 说明是旧版/残留状态，直接清掉。
        if (pid <= 0) {
            clearStateFiles(dir)
            current = null
            return@withLock publish(State.Off)
        }
        if (shizukuGranted()) {
            val r = runCatching { ShizukuShell.exec("kill -0 $pid", 5_000) }.getOrNull()
            if (r != null && r.code != 0) {
                clearStateFiles(dir)
                current = null
                return@withLock publish(State.Off)
            }
        }
        val meta = runCatching {
            File(dir, "meta").readText().split(",").map { it.trim().toInt() }
        }.getOrNull()
        val w = meta?.getOrNull(0) ?: 720
        val h = meta?.getOrNull(1) ?: 1280
        val dpi = meta?.getOrNull(2) ?: 320
        val info = Info(id, "WorkBuddy 虚拟屏", w, h, dpi, "$w×$h@$dpi")
        current = info
        publish(State.On(info))
    }

    // ── 画面 ──────────────────────────────────────────────────────────

    /**
     * 读取服务写出的最新帧（PNG）。文件未变化时复用上一张 Bitmap。
     */
    suspend fun readFrame(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(vdsDir(context), "frame.png")
        if (!file.exists() || file.length() <= 0L) return@withContext null
        val stamp = file.lastModified()
        synchronized(frameLock) {
            if (stamp == lastFrameStamp && lastFrame != null) return@withContext lastFrame
            val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                ?: return@withContext null
            lastFrame = bitmap
            lastFrameStamp = stamp
            lastFrameAt = System.currentTimeMillis()
            bitmap
        }
    }

    fun latestFrameAt(): Long = lastFrameAt

    fun latestShotPath(context: Context): String =
        File(File(context.cacheDir, "vdisplay"), "latest.png").absolutePath

    /** 把最新帧复制成 Agent 可读的 latest.png，返回路径与尺寸。 */
    suspend fun shot(context: Context): Shot? = withContext(Dispatchers.IO) {
        val frame = File(vdsDir(context), "frame.png")
        if (!frame.exists() || frame.length() <= 0L) return@withContext null
        val outDir = File(context.cacheDir, "vdisplay").apply { mkdirs() }
        val target = File(outDir, "latest.png")
        runCatching { frame.copyTo(target, overwrite = true) }.getOrElse { return@withContext null }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@withContext null
        Shot(target.absolutePath, opts.outWidth, opts.outHeight)
    }

    // ── 输入注入（Shizuku：input -d） ─────────────────────────────────

    suspend fun tap(context: Context, x: Int, y: Int): Result<Unit> =
        input("tap ${x.coerceAtLeast(0)} ${y.coerceAtLeast(0)}")

    suspend fun swipe(
        context: Context,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 300,
    ): Result<Unit> =
        input(
            "swipe ${x1.coerceAtLeast(0)} ${y1.coerceAtLeast(0)} " +
                "${x2.coerceAtLeast(0)} ${y2.coerceAtLeast(0)} ${durationMs.coerceIn(50, 3000)}",
        )

    suspend fun key(context: Context, code: Int): Result<Unit> = input("keyevent $code")

    suspend fun text(context: Context, value: String): Result<Unit> {
        if (value.isEmpty()) return Result.failure(IllegalArgumentException("text 为空"))
        val info = current ?: return Result.failure(IllegalStateException("虚拟屏未开启"))
        val ascii = value.all { it.code in 0x20..0x7E }
        if (ascii) {
            val escaped = value.replace(" ", "%s").replace("'", "'\\''")
            return input("text '$escaped'")
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

    private suspend fun input(args: String): Result<Unit> {
        val info = current ?: return Result.failure(IllegalStateException("虚拟屏未开启"))
        if (!shizukuGranted()) return Result.failure(IllegalStateException("输入注入需要 Shizuku 授权"))
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

    /**
     * 用 `am start --display <id>` 启动目标 App。调用者是显示 owner（同为 shell/root），
     * 因此不会触发普通 App 的「虚拟屏限制」或 HyperOS 的跨应用「应用跳转」确认。
     */
    suspend fun launch(context: Context, packageName: String): Result<String> {
        if (!supported()) {
            return Result.failure(IllegalStateException("这台设备不支持虚拟屏（需要 Android 8+）"))
        }
        if (packageName == context.packageName) {
            return Result.failure(IllegalStateException("不要把 WorkBuddy 自己启动到虚拟屏（会导致两个控制台实例）"))
        }
        val info = current ?: return Result.failure(IllegalStateException("虚拟屏未开启"))
        if (!shizukuGranted()) return Result.failure(IllegalStateException("启动应用需要 Shizuku 授权"))
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val activity = runCatching { pm.queryIntentActivities(query, 0) }
            .getOrDefault(emptyList())
            .firstOrNull()
            ?.activityInfo
            ?: return Result.failure(IllegalStateException("找不到 $packageName 的启动入口（未安装或没有桌面图标）"))
        val component = ComponentName(activity.packageName, activity.name)
        // 单实例 App（微信等）在真机上已有任务时，am start --display 只会把旧任务带到前台，
        // 看起来像「启动不生效」。先 force-stop，确保在虚拟屏上创建一个新任务。
        runCatching { ShizukuShell.exec("am force-stop $packageName", 10_000) }
        val r = runCatching {
            ShizukuShell.exec(
                "am start --display ${info.displayId} -n '${component.flattenToString()}'",
                25_000,
            )
        }.getOrNull() ?: return Result.failure(IllegalStateException("Shizuku 执行失败"))
        val out = r.output
        val failed = r.code != 0 ||
            out.contains("Error:", ignoreCase = true) ||
            out.contains("Exception", ignoreCase = true) ||
            out.contains("Permission Denial", ignoreCase = true)
        if (failed) {
            return Result.failure(IllegalStateException("启动失败（退出码 ${r.code}）：${out.take(300)}"))
        }
        // 给 App 的 splash/登录重定向留时间，再核对任务到底落在哪块屏。
        delay(2_000)
        val dump = runCatching {
            ShizukuShell.exec("dumpsys activity activities", 20_000)
        }.getOrNull()?.output ?: return Result.failure(IllegalStateException("无法读取任务栈，启动结果未知"))
        val displays = displaysOf(dump, packageName)
        return when {
            info.displayId in displays ->
                Result.success("已在虚拟屏启动 $packageName")
            displays.isEmpty() ->
                Result.failure(
                    IllegalStateException(
                        "启动后任务栈里没有 $packageName（可能闪退或被系统冻结）。换支持副屏的 App 试，或把 logcat 发我。",
                    ),
                )
            else ->
                Result.failure(
                    IllegalStateException(
                        "系统把 $packageName 放到了真机（display ${displays.sorted().joinToString("/")}），" +
                            "没有落在虚拟屏（display ${info.displayId}）。微信等带登录/单实例重定向的 App 会拒绝副屏；" +
                            "换支持副屏的 App，或改用真机工具。",
                    ),
                )
        }
    }

    /** 从 `dumpsys activity activities` 中找某包出现过的 Display 编号。 */
    private fun displaysOf(dump: String, packageName: String): Set<Int> {
        val result = linkedSetOf<Int>()
        var display = -1
        for (line in dump.lineSequence()) {
            Regex("""Display #(\d+)""").find(line)?.let { display = it.groupValues[1].toInt() }
            if (display >= 0 && line.contains("packageName=$packageName")) result += display
        }
        return result
    }

    // ── 文件工具 ──────────────────────────────────────────────────────

    private fun vdsDir(context: Context): File = File(context.cacheDir, "vds")

    private fun clearStateFiles(dir: File) {
        deleteQuietly(File(dir, "id"))
        deleteQuietly(File(dir, "pid"))
        deleteQuietly(File(dir, "frame.png"))
        deleteQuietly(File(dir, "meta"))
        deleteQuietly(File(dir, "log"))
    }

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    private fun publish(next: State): State {
        _state.value = next
        return next
    }
}
