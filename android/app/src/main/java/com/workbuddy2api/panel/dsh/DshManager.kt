package com.workbuddy2api.panel.dsh

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
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
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var tokenUrl: String? = null
    @Volatile private var tokenConsumed: Boolean = false

    fun rootDir(): File = File(app.filesDir, "dsh").apply { mkdirs() }
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
    fun extractedInfo(): String? = File(rootDir(), EXTRACTED_MARK).takeIf { it.isFile }?.readText()

    suspend fun start(): State = mutex.withLock {
        val cur = _state.value
        if (cur is State.Running && process != null) return@withLock cur
        val result = try {
            withContext(Dispatchers.IO) { doStart() }
        } catch (e: Exception) {
            State.Failed(e.message?.takeIf { it.isNotBlank() } ?: "DSH 启动异常")
        }
        _state.value = result
        result
    }

    private fun doStart(): State {
        val root = rootDir()
        val mark = File(root, EXTRACTED_MARK)
        if (!mark.isFile) {
            _state.value = State.Extracting(0)
            val n = extractPayload(root)
            mark.writeText("entries=$n ms-marker")
        }
        syncManagedPatch(root)
        val node = File(app.applicationInfo.nativeLibraryDir, "libdshnode.so")
        val binjs = File(root, "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!node.isFile) return State.Failed("未找到 node（libdshnode.so），请重新安装 APK")
        if (!binjs.isFile) return State.Failed("内核树不完整（缺少 bin.js），请清除应用数据重试")

        killStaleNode()

        val log = logFile()
        rotateLogIfNeeded(log)
        val port = pickPort()
        val logOffset = if (log.isFile) log.length() else 0L
        log.appendText("\n===== ${java.time.OffsetDateTime.now()} 启动 DSH（端口 $port） =====\n")

        val pb = ProcessBuilder(
            node.absolutePath, "--expose-internals", binjs.absolutePath,
            "web", "--host", "127.0.0.1", "--port", port.toString(), "--no-open",
        )
        pb.directory(root)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir
        env["HOME"] = app.filesDir.absolutePath
        env["DSH_HOME"] = File(root, "dshhome").absolutePath
        env["DSH_WORKSPACE"] = workspaceDir().absolutePath
        env["TMPDIR"] = app.cacheDir.absolutePath
        env["TERM"] = "xterm"
        env["SHELL"] = "/system/bin/sh"
        env["PATH"] = listOf(
            File(root, "bin").absolutePath,
            app.applicationInfo.nativeLibraryDir,
            "/system/bin", "/system/xbin",
        ).joinToString(":")
        env["SSL_CERT_FILE"] = File(root, "runtime/etc/cacert.pem")
            .takeIf { it.isFile }?.absolutePath ?: File(app.filesDir, "gateway/cacert.pem")
            .takeIf { it.isFile }?.absolutePath ?: ""
        env["OPENSSL_CONF"] = File(root, "runtime/etc/openssl.cnf").absolutePath
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
            val shared = File(StorageAccess.sharedRoot(), StorageAccess.sharedWorkspaceName())
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


    /** cordis.patch.yml 由本 App 托管：老版本（官方补丁/用户改坏）启动前刷新为干净版。 */
    private fun syncManagedPatch(root: File) {
        val target = File(root, "dshhome/cordis.patch.yml")
        if (target.isFile) {
            val cur = runCatching { target.readText() }.getOrDefault("")
            if (cur.contains("WorkBuddy-managed-patch: v2")) return
        }
        runCatching {
            java.util.zip.ZipInputStream(app.assets.open("payload-dsh.zip")).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.name == "dshhome/cordis.patch.yml") {
                        target.parentFile?.mkdirs()
                        java.io.FileOutputStream(target).use { zin.copyTo(it) }
                        zin.closeEntry()
                        return
                    }
                    zin.closeEntry()
                }
            }
        }
    }
    private fun extractPayload(root: File): Int {
        var n = 0
        java.util.zip.ZipInputStream(app.assets.open("payload-dsh.zip")).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(root, e.name)
                val clean = f.canonicalPath.startsWith(root.canonicalPath)
                if (!clean) { zin.closeEntry(); continue }   // 防路径穿越
                if (e.isDirectory) { f.mkdirs() } else {
                    f.parentFile?.mkdirs()
                    java.io.FileOutputStream(f).use { zin.copyTo(it) }
                }
                zin.closeEntry()
                n++
            }
        }
        return n
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
