package com.workbuddy2api.panel.dsh

import android.app.Application
import android.net.Uri
import com.workbuddy2api.panel.util.DeviceEnv
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * DSH（DeepSeek Harness）内核 —— 独立 Node 进程管理。
 *
 * 架构：
 *  - node 二进制与其依赖库随 APK 打包进 jniLibs（libdshnode.so 等，Android 10+ 唯一合法 exec 位）；
 *  - 内核树（dshroot/dshhome/bin/runtime-etc）在 assets/payload-dsh.zip，首次启动解压到
 *    filesDir/dsh（约 28k 个文件、2~3 秒）；
 *  - 以独立进程运行 
ode bin.js web --host 127.0.0.1 --port P --no-open`，
 *    从进程输出解析一次性 token URL 供 WebView 使用；
 *  - 模型提供方由用户在 DSH 控制台里配置为本地网关（http://127.0.0.1:7863/v1）。
 */
class DshManager private constructor(private val app: Application) {

    companion object {
        private const val TAG = "WbDsh"
        private const val PORT_START = 8300
        private const val PORT_TRIES = 10
        private const val EXTRACTED_MARK = ".extracted"
        private val TOKEN_URL = Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_\\-]+")

        @Volatile private var instance: DshManager? = null
        fun get(context: Context): DshManager =
            instance ?: synchronized(this) {
                instance ?: DshManager(context.applicationContext as Application).also { instance = it }
            }
    }

    /** 运行状态。url 为带一次性 token 的控制台地址。 */
    sealed interface State {
        data object Stopped : State
        data class Extracting(val entries: Int) : State
        data class Starting(val message: String) : State
        data class Running(val url: String) : State
        data class Failed(val message: String) : State
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(State.Stopped)

    /** 内核进程的生命周期不跟 UI 走：启动/重启都跑在这里，页面离开也不会被取消。 */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 后台启动（界面调用它，Composable 离开组合也不会中断启动）。 */
    fun startAsync(): Job = bg.launch { runCatching { start() } }

    fun restartAsync(): Job = bg.launch { runCatching { restart() } }

    fun startSafeModeAsync(): Job = bg.launch { runCatching { startSafeMode() } }
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var tokenUrl: String? = null
    @Volatile private var tokenConsumed: Boolean = false

    /**
     * 只读内核树（dshroot / bin）根目录。
     *
     * 拿到「所有文件访问」时外置到 /storage/emulated/0/WorkBuddy/dsh，省内部存储；
     * 否则退回内部 filesDir/dsh-kernel。
     */
    fun kernelDir(): File {
        val internal = File(app.filesDir, "dsh-kernel")
        if (!StorageAccess.hasAllFilesAccess(app)) return internal.apply { mkdirs() }
        val shared = File(File(StorageAccess.sharedRoot(), StorageAccess.sharedWorkspaceName()), "dsh")
        if (runCatching { shared.mkdirs() }.isSuccess || shared.isDirectory) return shared
        return internal.apply { mkdirs() }
    }

    /**
     * 用户态目录（dshhome / runtime / 凭证 / 日志）——必须留在内部存储：
     * DSH 的 credentials-local 会 chmod 600，而 /sdcard 的 FUSE 不支持 chmod/所有权。
     */
    fun homeDir(): File = File(app.filesDir, "dsh-home").apply { mkdirs() }

    /** 内核树位置（设置页展示用）。 */
    fun kernelRootPath(): String = kernelDir().absolutePath

    /** 内核树是否位于共享存储。 */
    fun kernelOnSharedStorage(): Boolean =
        kernelDir().absolutePath.startsWith(StorageAccess.sharedRoot().absolutePath)
    fun logFile(): File = File(app.filesDir, "dsh-web.log")
    fun isRunning(): Boolean = process != null && _state.value is State.Running

    /** WebView 应该加载的地址：token 未消费用带 token 的一次性 URL，消费后走 cookie 会话的 base URL。 */
    fun webUrl(): String? {
        val url = tokenUrl ?: return null
        val base = url.substringBefore("/?")
        return if (tokenConsumed) base else url
    }

    fun baseUrl(): String? = tokenUrl?.substringBefore("/?")

    /** 页面成功加载后调用：token 已换到 cookie，后续不要再访问一次性 URL。 */
    fun markTokenConsumed() { tokenConsumed = true }

    /** 已解压标记（含条目数与耗时），null 表示首启还需解压。 */
    fun extractedInfo(): String? = File(kernelDir(), EXTRACTED_MARK).takeIf { it.isFile }?.readText()

    suspend fun start(): State = mutex.withLock {
        val cur = _state.value
        if (cur is State.Running && process != null) return@withLock cur
        val result = try {
            withContext(NonCancellable + Dispatchers.IO) { doStart() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            State.Failed(e.message?.takeIf { it.isNotBlank() } ?: "DSH 启动异常")
        }
        if (result is State.Failed) bumpFailure(result.message) else resetFailures()
        _state.value = result
        result
    }

    private fun doStart(): State {
        if (!DeviceEnv.supportsDsh()) {
            return State.Failed(
                "当前设备架构为 ${DeviceEnv.abiSummary()}：内置 Node 运行时只提供 arm64，" +
                    "DSH 内核不可用；网关与原生助手仍可正常使用。",
            )
        }
        // 先结束上一次的 node（否则它的 cwd 还指着旧目录，迁移后删不掉旧树）
        killStaleNode()
        val kernel = kernelDir()
        val home = homeDir()
        ensureLayout(kernel, home)
        // 宿主回环桥（插件发通知等）；失败不影响内核启动
        runCatching { LocalBridge.start(app) }
        // 内核命令 notify（走回环桥发通知）；清掉旧版本尝试过的插件登记
        val notifyScript = runCatching { NotifyCli.ensure(app, home) }.getOrNull()
        ensureBinShims(home)
        // 内核 bin/ 里那套 Termux 的 pnpm/npm（在 /sdcard 上不可执行）会污染 PATH 解析，删掉
        runCatching { for (n in listOf("pnpm", "pnpx", "npm", "npx")) File(kernel, "bin/" + n).delete() }
        // profile 的 pnpm 元数据和内置 pnpm 版本对齐，否则 DSH 自己的插件安装会报 UNEXPECTED_STORE
        runCatching { PluginInstaller.alignStoreDir(app, home) }
        InstalledPlugins.removeById(app, NotifyPlugin.ID)
        syncManagedPatch(home)
        val replayed = runCatching { UserPatches.replay(kernel, home) }.getOrDefault(0)
        val node = File(app.applicationInfo.nativeLibraryDir, "libdshnode.so")
        val binjs = File(kernel, "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!node.isFile) return State.Failed("未找到 node（libdshnode.so），请重新安装 APK")
        if (!binjs.isFile) return State.Failed("内核树不完整（缺少 bin.js），请清除应用数据重试")
        runCatching { ensurePnpmRuntime(home, node) }

        val log = logFile()
        rotateLogIfNeeded(log)
        val port = pickPort()
        val logOffset = if (log.isFile) log.length() else 0L
        log.appendText("\n===== ${java.time.OffsetDateTime.now()} 启动 DSH（端口 $port） =====\n")

        val pb = ProcessBuilder(
            node.absolutePath, "--expose-internals", binjs.absolutePath,
            "web", "--host", "127.0.0.1", "--port", port.toString(), "--no-open",
        )
        pb.directory(home)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir
        env["HOME"] = app.filesDir.absolutePath
        env["DSH_HOME"] = File(home, "dshhome").absolutePath
        env["DSH_WORKSPACE"] = workspaceDir().absolutePath
        env["TMPDIR"] = app.cacheDir.absolutePath
        env["TERM"] = "xterm"
        env["SHELL"] = "/system/bin/sh"
        env["WB_BRIDGE_TOKEN"] = LocalBridge.token(app)
        notifyScript?.let { env["WB_NOTIFY"] = it.absolutePath }
        env["PREFIX"] = File(home, "toolchain/usr").absolutePath
        env["GIT_EXEC_PATH"] = File(home, "toolchain/usr/libexec/git-core").absolutePath
        env["GIT_TEMPLATE_DIR"] = File(home, "toolchain/usr/share/git-core/templates").absolutePath
        env["WB_NODE"] = node.absolutePath
        env["WB_TOOLCHAIN_BIN"] = File(home, "toolchain/usr/bin").absolutePath
        env["WB_TOOLS_BIN"] = File(home, "tools/node_modules/.bin").absolutePath
        env["WB_DSH_HOME"] = home.absolutePath
        env["PATH"] = listOf(
            File(home, "tools/node_modules/.bin").absolutePath,
            File(home, "toolchain/usr/bin").absolutePath,
            File(kernel, "bin").absolutePath,
            app.applicationInfo.nativeLibraryDir,
            "/system/bin", "/system/xbin",
        ).joinToString(":")
        // 优先用系统证书合成的 bundle（企业代理/自签 CA 可用），其次 payload 自带的 Mozilla 根证书。
        val sysCa = SystemCaBundle.ensure(app, File(home, "runtime/etc"))
        env["SSL_CERT_FILE"] = sysCa?.absolutePath
            ?: File(home, "runtime/etc/cacert.pem").takeIf { it.isFile }?.absolutePath
            ?: File(app.filesDir, "gateway/cacert.pem").takeIf { it.isFile }?.absolutePath
            ?: ""
        // 扩展中心工具链：部分二进制把前缀编译进去了，只能靠环境变量覆盖
        val tusr = File(home, "toolchain/usr")
        File(tusr, "etc/tls/cert.pem").takeIf { it.isFile }?.let {
            env["GIT_SSL_CAINFO"] = it.absolutePath
            env["CURL_CA_BUNDLE"] = it.absolutePath
            env["SSL_CERT_FILE"] = it.absolutePath
        }
        if (File(tusr, "lib/python3.14").isDirectory) env["PYTHONHOME"] = tusr.absolutePath
        env["OPENSSL_CONF"] = File(home, "runtime/etc/openssl.cnf").absolutePath
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_PAGER"] = "cat"

        val p = try {
            pb.start()
        } catch (e: Exception) {
            return State.Failed("无法启动 node 进程：${e.message ?: e.javaClass.simpleName}")
        }
        process = p
        rememberPid(p)
        pumpLog(p, log)

        // 健康检查：等 token URL 打印 + HTTP 响应
        val deadline = System.currentTimeMillis() + 45_000
        var url: String? = null
        while (System.currentTimeMillis() < deadline) {
            if (!p.aliveCompat()) {
                val tail = logTail(24)
                process = null
                return State.Failed("DSH 进程退出：\n$tail")
            }
            if (url == null) url = findTokenUrl(logOffset)
            if (url != null && ping(port)) break
            Thread.sleep(400)
        }
        val finalUrl = url ?: findTokenUrl(logOffset)
            ?: return State.Failed("未取得 DSH 控制台地址（token 超时）：\n${logTail(24)}")
        tokenUrl = finalUrl
        tokenConsumed = false
        return State.Running(finalUrl)
    }

    fun stop() {
        val p = process
        process = null
        if (p != null) {
            runCatching {
                p.destroy()
                if (!p.waitForCompat(4_000)) p.destroyForciblyCompat()
            }
        }
        _state.value = State.Stopped
    }

    suspend fun restart(): State {
        stop()
        Thread.sleep(400)
        return start()
    }

    fun logTail(maxLines: Int = 120): String {
        val f = logFile()
        if (!f.isFile) return ""
        return runCatching {
            val len = f.length()
            val start = (len - 96 * 1024).coerceAtLeast(0)
            val bytes = ByteArray((len - start).toInt())
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start); raf.readFully(bytes)
            }
            String(bytes, Charsets.UTF_8).lineSequence()
                .filter { it.isNotBlank() }.toList().takeLast(maxLines).joinToString("\n")
        }.getOrDefault("")
    }

    /** AI 工作区（bash/文件工具的 cwd），放应用专属外部目录，卸载可清、用户可见。 */
    /**
     * AI 工作区（bash / 文件工具的 cwd）。
     *
     * 拿到「所有文件访问」后优先用共享存储 /storage/emulated/0/WorkBuddy，
     * DSH 的工具就能直接检索、读写手机文件；未授权时退回应用专属外部目录。
     */
    fun workspaceDir(): File {
        if (StorageAccess.hasAllFilesAccess(app)) {
            val shared = File(File(StorageAccess.sharedRoot(), StorageAccess.sharedWorkspaceName()), "workspace")
            if (runCatching { shared.mkdirs() }.isSuccess || shared.isDirectory) return shared
        }
        return File(app.getExternalFilesDir(null) ?: app.filesDir, "workspace").apply { mkdirs() }
    }


    /** 记录 node 进程 pid，供下次启动时清理孤儿进程（同 uid 可直接 kill）。 */
    private fun rememberPid(p: Process) {
        // Process#pid 是 API 26；用反射兼容 minSdk 24，拿不到就跳过（仅影响孤儿清理）。
        val pid: Long = runCatching {
            val m = Process::class.java.getMethod("pid")
            (m.invoke(p) as? Long) ?: -1L
        }.getOrDefault(-1L)
        if (pid > 0) {
            runCatching { File(app.filesDir, "dsh-node.pid").writeText(pid.toString()) }
        }
    }

    /** 清理上次异常退出遗留的 node（避免端口与内存被孤儿进程长期占用）。 */
    private fun killStaleNode() {
        val f = File(app.filesDir, "dsh-node.pid")
        if (!f.isFile) return
        val pid = runCatching { f.readText().trim().toInt() }.getOrNull()
        runCatching { f.delete() }
        if (pid == null || pid <= 0) return
        runCatching { android.os.Process.killProcess(pid) }
        Thread.sleep(250)
    }
    // ───────────────────────── P2：插件开关 / 安全模式 / 备份 / 我的补丁 ─────────────────────────

    private fun statePrefs() = app.getSharedPreferences("wb_dsh_state", android.content.Context.MODE_PRIVATE)

    private fun bumpFailure(message: String) {
        val p = statePrefs()
        val count = p.getInt("fail_count", 0) + 1
        p.edit().putInt("fail_count", count).putString("last_error", message.take(2000)).apply()
    }

    private fun resetFailures() {
        statePrefs().edit().putInt("fail_count", 0).remove("last_error").apply()
    }

    /** 连续启动失败次数与最后一次错误（供 UI 提示安全模式）。 */
    fun failureInfo(): Pair<Int, String> {
        val p = statePrefs()
        return p.getInt("fail_count", 0) to p.getString("last_error", "").orEmpty()
    }

    /**
     * 安全模式：把 profile 用户层整体旁置、用出厂 profile 启动。
     * 会话/凭证（dshhome 下其它文件）保持不动。
     */
    suspend fun startSafeMode(): State = mutex.withLock {
        val home = homeDir()
        val profiles = File(home, "dshhome/profiles")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        runCatching {
            if (profiles.isDirectory) {
                profiles.copyRecursively(File(home, "dshhome/profiles.bak-$stamp"), overwrite = true)
                profiles.deleteRecursively()
            }
            restoreProfileFromPayload(File(home, "dshhome/profiles"))
        }
        resetFailures()
        val result = try {
            withContext(NonCancellable + Dispatchers.IO) { doStart() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            State.Failed(e.message?.takeIf { it.isNotBlank() } ?: "DSH 安全模式启动异常")
        }
        if (result is State.Failed) bumpFailure(result.message)
        _state.value = result
        result
    }

    /** 从 payload 重新解压 dshhome/profiles 子树（出厂配置）。 */
    private fun restoreProfileFromPayload(dest: File) {
        java.util.zip.ZipInputStream(app.assets.open("payload-dsh.zip")).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.name.startsWith("dshhome/profiles/")) { zin.closeEntry(); continue }
                val f = File(dest, e.name.removePrefix("dshhome/profiles/"))
                if (!f.canonicalPath.startsWith(dest.canonicalPath)) { zin.closeEntry(); continue }
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    java.io.FileOutputStream(f).use { zin.copyTo(it) }
                }
                zin.closeEntry()
            }
        }
    }

    fun plugins(): List<DshPlugins.Plugin> =
        runCatching { DshPlugins.scan(app, kernelDir(), homeDir()) }.getOrDefault(emptyList())

    fun setPluginDisabled(id: String, disabled: Boolean) = DshPlugins.setUserDisabled(app, id, disabled)

    fun myPatchCount(): Int = runCatching { UserPatches.patchCount(homeDir()) }.getOrDefault(0)

    /** 把当前内核树的用户改动保存为补丁，返回保存文件数。 */
    fun saveMyPatches(): Int = runCatching { UserPatches.capture(kernelDir(), homeDir()) }.getOrDefault(0)

    /** 清空我的补丁，并让内核树下次启动重新解压（回出厂）。 */
    fun clearMyPatches() = runCatching { UserPatches.clear(homeDir(), kernelDir()) }

    /** 应用更新后 nativeLibraryDir 会变，启动时刷新一次 home/bin 的符号链接。 */
    fun refreshBinShims() {
        runCatching { ensureBinShims(homeDir()) }
    }

    fun toolchainInstalled(id: String): Boolean = Toolchain.installed(homeDir(), id)

    fun toolchainInstalledIds(): List<String> = Toolchain.installedIds(homeDir())

    suspend fun installToolchain(id: String, onLog: (String) -> Unit = {}): Toolchain.Result {
        val r = Toolchain.install(app, homeDir(), id, app.cacheDir, onLog)
        if (r.ok) runCatching { ensureBinShims(homeDir()) }
        return r
    }

    fun uninstallToolchain(id: String): Toolchain.Result = Toolchain.uninstall(homeDir(), id)

    fun npmAvailable(): Boolean = PluginInstaller.npmAvailable(app, homeDir())

    fun pluginInstalled(pkg: String): Boolean = PluginInstaller.pluginInstalled(homeDir(), pkg)

    suspend fun installPlugin(pkg: String): PluginInstaller.Result {
        val r = PluginInstaller.plugin(app, homeDir(), pkg, "install")
        if (r.ok) {
            InstalledPlugins.add(app, pkg.substringAfterLast('/'), pkg)
            syncManagedPatch(homeDir())
        }
        return r
    }

    suspend fun uninstallPlugin(pkg: String): PluginInstaller.Result {
        val r = PluginInstaller.plugin(app, homeDir(), pkg, "uninstall")
        if (r.ok) {
            InstalledPlugins.remove(app, pkg)
            syncManagedPatch(homeDir())
        }
        return r
    }

    fun toolInstalled(pkg: String): Boolean = PluginInstaller.toolInstalled(homeDir(), pkg)

    suspend fun installTool(pkg: String): PluginInstaller.Result =
        PluginInstaller.tool(app, homeDir(), pkg, "install")

    suspend fun uninstallTool(pkg: String): PluginInstaller.Result =
        PluginInstaller.tool(app, homeDir(), pkg, "uninstall")

    fun exportBackup(uri: Uri): Result<DshBackup.ExportResult> =
        DshBackup.export(app, homeDir(), uri)

    fun importBackup(uri: Uri): Result<DshBackup.ImportResult> =
        DshBackup.import(app, homeDir(), uri)
    /**
     * 在 home/bin 里创建 pnpm / npm / node 的符号链接，指向 jniLibs 里的 ELF 启动器。
     *
     * 为什么需要：App 数据目录里的脚本无法 exec（SELinux），而 DSH 插件安装是
     * `spawn("pnpm")`；符号链接指向 nativeLibraryDir 的可执行文件（标签允许 exec），
     * 启动器再按 argv[0] 决定跑 pnpm.cjs / npm-cli.js。
     */
    /** 首次启动时用内置 npm 装一个与应用前缀无关的 pnpm（payload 自带的 pnpm 硬编码 Termux 前缀）。 */
    private fun ensurePnpmRuntime(home: File, node: File) {
        // 已内置纯 JS 的 pnpm（payload: pnpm10/），不需要联网安装
        if (File(home, "pnpm10/bin/pnpm.cjs").isFile) return
        val entry = File(home, "pnpmjs/node_modules/pnpm/bin/pnpm.cjs")
        // pnpm>=9 会拉原生 @pnpm/exe（应用数据目录不可 exec），只认纯 JS 的 pnpm 10
        if (entry.isFile) return
        runCatching { File(home, "pnpmjs").deleteRecursively() }
        Thread({
            runCatching {
                val npmCli = File(home, "npm/bin/npm-cli.js")
                if (!npmCli.isFile) return@runCatching
                val pb = ProcessBuilder(
                    node.absolutePath, npmCli.absolutePath, "install", "pnpm@10",
                    "--prefix", File(home, "pnpmjs").absolutePath,
                    "--registry=https://registry.npmmirror.com",
                    "--omit=optional", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error",
                )
                pb.directory(home)
                pb.redirectErrorStream(true)
                val e = pb.environment()
                e["PATH"] = listOf(File(home, "bin").absolutePath, app.applicationInfo.nativeLibraryDir, "/system/bin").joinToString(":")
                e["HOME"] = app.filesDir.absolutePath
                e["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir
                e["WB_NODE"] = node.absolutePath
                e["WB_DSH_HOME"] = home.absolutePath
                e["npm_config_cache"] = File(home, "npm-cache").absolutePath
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitForCompat(180_000)
                logFile().appendText("\n[pnpm] install exit=${runCatching { p.exitValue() }.getOrDefault(-1)}\n" + out.takeLast(1200) + "\n")
            }
        }, "wb-pnpm-bootstrap").apply { isDaemon = true; start() }
    }

    private fun ensureBinShims(home: File) {
        runCatching {
            val bin = File(home, "bin").apply { mkdirs() }
            val shim = File(app.applicationInfo.nativeLibraryDir, "libwbnode_shim.so")
            if (!shim.isFile) return
            // 工具链里的每个命令也建链接：应用进程无法直接 exec files/ 下的二进制，
            // 由启动器用 linker64 加载（见 android/dsh/shim.c）。
            for (dir in listOf(File(home, "toolchain/usr/bin"), File(home, "tools/node_modules/.bin"))) {
                val names = dir.list() ?: continue
                for (n in names) {
                    if (n == "node" || n == "npm" || n == "pnpm") continue
                    val target = File(dir, n)
                    if (!target.isFile) continue
                    val link = File(bin, n)
                    runCatching { link.delete() }
                    runCatching { android.system.Os.symlink(shim.absolutePath, link.absolutePath) }
                }
            }
            for (name in listOf("pnpm", "npm", "node")) {
                val link = File(bin, name)
                // 每次启动重建：应用更新后 nativeLibraryDir 路径会变，旧链接会失效
                runCatching { link.delete() }
                runCatching { android.system.Os.symlink(shim.absolutePath, link.absolutePath) }
            }
        }
    }
    private fun pickPort(): Int {
        for (i in 0 until PORT_TRIES) {
            val port = PORT_START + i
            val free = runCatching {
                java.net.ServerSocket(port, 4, java.net.InetAddress.getByName("127.0.0.1")).use { true }
            }.getOrDefault(false)
            if (free) return port
        }
        return PORT_START
    }


    /** cordis.patch.yml 由本 App 托管：内置策略 + 用户插件开关，每次启动重写。 */
    private fun syncManagedPatch(home: File) {
        runCatching { DshPlugins.writePatch(app, home) }
    }

    /**
     * 解压内置 payload，按内容分流：
     * dshroot/ 与 bin/ 进只读内核树（可能在外置存储），其余（dshhome/runtime/etc）进内部 home。
     */
    private fun extractPayload(kernel: File, home: File): Int {
        var n = 0
        java.util.zip.ZipInputStream(app.assets.open("payload-dsh.zip")).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val isKernel = e.name.startsWith("dshroot/") || e.name.startsWith("bin/")
                val base = if (isKernel) kernel else home
                val f = File(base, e.name)
                val clean = f.canonicalPath.startsWith(base.canonicalPath)
                if (!clean) { zin.closeEntry(); continue }   // 防路径穿越
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    java.io.FileOutputStream(f).use { zin.copyTo(it) }
                }
                zin.closeEntry()
                n++
            }
        }
        return n
    }

    /**
     * 增量补齐 payload 里的可选组件（如内置 pnpm10）。
     * 老版本升级后 .extracted 标记还在，全量重解压会覆盖用户 dshhome 数据，所以这里只补缺失的目录。
     */
    private fun ensureExtraPayload(home: File) {
        if (File(home, "pnpm10/bin/pnpm.cjs").isFile) return
        runCatching {
            java.util.zip.ZipInputStream(app.assets.open("payload-dsh.zip")).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.name.startsWith("pnpm10/")) { zin.closeEntry(); continue }
                    val f = File(home, e.name)
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        java.io.FileOutputStream(f).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                }
            }
        }
    }

    /** 确保内核树/用户态目录就位：拆分迁移旧合并树、清掉残留、缺文件则解压。 */
    private fun ensureLayout(kernel: File, home: File) {
        val kernelMark = File(kernel, EXTRACTED_MARK)
        val homeMark = File(home, EXTRACTED_MARK)

        // 1) 旧版「合并树」filesDir/dsh → 拆分到 kernel/home
        val legacy = File(app.filesDir, "dsh")
        if (legacy.isDirectory && File(legacy, EXTRACTED_MARK).isFile) {
            _state.value = State.Extracting(0)
            moveInto(File(legacy, "dshroot"), File(kernel, "dshroot"))
            moveInto(File(legacy, "bin"), File(kernel, "bin"))
            moveInto(File(legacy, "runtime"), File(home, "runtime"))
            moveInto(File(legacy, "dshhome"), File(home, "dshhome"))
            moveInto(File(legacy, "PAYLOAD-MANIFEST.json"), File(home, "PAYLOAD-MANIFEST.json"))
            if (!kernelMark.isFile) kernelMark.writeText("migrated")
            if (!homeMark.isFile) homeMark.writeText("migrated")
            legacy.deleteRecursively()
        }

        // 2) 外置目录里残留的 dshhome（早期实验）→ 内部，随后删除残留
        val strayHome = File(kernel, "dshhome")
        if (strayHome.isDirectory) {
            if (!File(home, "dshhome").isDirectory) moveInto(strayHome, File(home, "dshhome"))
            strayHome.deleteRecursively()
        }

        // 3) 清理早期「整树外置」实验残留在内核目录的 home 相关文件（内部已有副本）
        if (kernel.absolutePath.startsWith(StorageAccess.sharedRoot().absolutePath)) {
            runCatching {
                if (File(home, "runtime/etc").isDirectory) File(kernel, "runtime").deleteRecursively()
                if (File(home, "PAYLOAD-MANIFEST.json").isFile) File(kernel, "PAYLOAD-MANIFEST.json").delete()
            }
        }

        // 4) 仍缺文件 → 按需解压（dshroot/bin → kernel，其余 → home）
        if (!kernelMark.isFile || !homeMark.isFile) {
            _state.value = State.Extracting(0)
            val n = extractPayload(kernel, home)
            kernelMark.writeText("entries=$n")
            homeMark.writeText("entries=$n")
        }
        ensureExtraPayload(home)

        // 首次解压后记录出厂基线（供「我的补丁」比对）
        runCatching { UserPatches.ensureBaseline(kernel, home) }
    }

    /** 移动目录/文件（跨存储用复制+删除，同存储优先 rename）。 */
    private fun moveInto(src: File, dst: File) {
        if (!src.exists()) return
        runCatching {
            if (dst.exists()) dst.deleteRecursively()
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) return
            if (src.isDirectory) {
                src.walkTopDown().forEach { f ->
                    val target = File(dst, f.relativeTo(src).path)
                    if (f.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        f.copyTo(target, overwrite = true)
                    }
                }
            } else {
                src.copyTo(dst, overwrite = true)
            }
            src.deleteRecursively()
        }.onFailure { e ->
            runCatching { logFile().appendText("\n[布局迁移失败] ${e.message}\n") }
        }
    }
    private fun findTokenUrl(afterOffset: Long = 0L): String? {
        val f = logFile(); if (!f.isFile) return null
        val text = runCatching {
            val len = f.length()
            val start = (len - 512 * 1024).coerceAtLeast(0).coerceAtLeast(afterOffset)
            val bytes = ByteArray((len - start).toInt())
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start); raf.readFully(bytes)
            }
            String(bytes, Charsets.UTF_8)
        }.getOrDefault("")
        val m = TOKEN_URL.matcher(text)
        var last: String? = null
        while (m.find()) { last = m.group() }
        return last
    }
    private fun ping(port: Int): Boolean = runCatching {
        val conn = java.net.URL("http://127.0.0.1:$port/").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 600; conn.readTimeout = 900
        try { conn.responseCode > 0 } finally { conn.disconnect() }
    }.getOrDefault(false)

    private fun pumpLog(p: Process, log: File) {
        Thread({
            runCatching {
                java.io.FileOutputStream(log, true).use { out ->
                    p.inputStream.use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n); out.flush()
                        }
                    }
                }
            }
        }, "wb-dsh-log").apply { isDaemon = true }.start()
    }

    private fun rotateLogIfNeeded(log: File) {
        if (log.isFile && log.length() > 8L * 1024 * 1024) {
            runCatching {
                val bytes = log.readBytes()
                log.writeBytes(bytes.copyOfRange(bytes.size - 512 * 1024, bytes.size))
            }
        }
    }

    private fun Process.aliveCompat(): Boolean = try { exitValue(); false } catch (e: IllegalThreadStateException) { true }
    private fun Process.waitForCompat(ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (!aliveCompat()) return true
            try { Thread.sleep(100) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return !aliveCompat() }
        }
        return !aliveCompat()
    }
    private fun Process.destroyForciblyCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { destroyForcibly() }
        } else {
            runCatching { destroy() }
        }
    }
}
