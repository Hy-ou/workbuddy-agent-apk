package com.workbuddy2api.panel.gateway

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 内置 Go 网关（workbuddy2api）的生命周期管理。
 *
 * 设计要点：
 * - 可执行文件随 APK 打包进 jniLibs（libwb2api.so，按 ABI 交叉编译），
 *   运行时位于 applicationInfo.nativeLibraryDir —— 这是 Android 10+ 唯一允许 exec 的位置；
 * - 工作目录固定在 filesDir/gateway（config.json / auths / data / gateway.log），
 *   首次启动自动生成带随机 api_key 的最小配置，其余字段由服务端默认值补齐；
 * - 只监听 127.0.0.1，外部网络无法访问；端口从 7863 起自动找空闲；
 * - GOOS=android + cgo 编译，DNS / 系统证书走 Android 原生实现；
 *   同时设置 SSL_CERT_FILE 兜底（内置 Mozilla CA bundle）。
 */
class LocalGatewayManager private constructor(private val app: Application) {

    companion object {
        private const val PORT_START = 7863
        private const val PORT_TRIES = 10
        private const val BINARY_NAME = "libwb2api.so"
        private const val HEALTH_PATH = "/healthz"

        @Volatile
        private var instance: LocalGatewayManager? = null

        fun get(context: Context): LocalGatewayManager {
            return instance ?: synchronized(this) {
                instance ?: LocalGatewayManager(context.applicationContext as Application).also { instance = it }
            }
        }
    }

    private val mutex = Mutex()
    private val _status = MutableStateFlow<GatewayStatus>(GatewayStatus.Stopped)
    val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    @Volatile
    private var process: Process? = null

    fun workDir(): File = File(app.filesDir, "gateway").apply { mkdirs() }

    fun logFile(): File = File(workDir(), "gateway.log")

    fun isRunning(): Boolean = process?.aliveCompat() == true && _status.value is GatewayStatus.Running

    /** 启动（幂等）。失败时返回 Failed 并保留原因。 */
    suspend fun start(): GatewayStatus = mutex.withLock {
        val current = _status.value
        if (current is GatewayStatus.Running && process?.aliveCompat() == true) {
            return@withLock current
        }
        _status.value = GatewayStatus.Starting("正在准备运行环境…")
        val result = try {
            withContext(Dispatchers.IO) { doStart() }
        } catch (e: Exception) {
            GatewayStatus.Failed(e.message?.takeIf { it.isNotBlank() } ?: "网关启动异常")
        }
        _status.value = result
        result
    }

    private fun doStart(): GatewayStatus {
        val binary = binaryFile()
        if (!binary.isFile) {
            return GatewayStatus.Failed("未找到网关可执行文件（当前设备 ABI 可能不受支持，仅提供 arm64-v8a / x86_64）")
        }
        if (!binary.canExecute()) binary.setExecutable(true, false)
        if (!binary.canExecute()) {
            return GatewayStatus.Failed("网关可执行文件没有执行权限：${binary.absolutePath}")
        }

        val config = ensureConfig()
        val key = readString(config, "api_key").orEmpty()
        var port = readPort(config)
        if (port <= 0) return GatewayStatus.Failed("配置文件里的 listen 端口无效")

        // 端口已被占用：若占用者就是上次遗留的本网关，直接接管；否则换空闲端口并写回配置。
        if (ping(port)) {
            if (isOurGateway(port)) {
                runCatching { GatewayService.start(app) }
                return GatewayStatus.Running(port, key)
            }
            val newPort = pickPort()
            if (newPort == port) return GatewayStatus.Failed("端口 $port 已被其它程序占用")
            rewritePort(config, newPort)
            port = newPort
        }

        val ca = ensureCaBundle()
        val log = logFile()
        log.parentFile?.mkdirs()
        // 日志超过 8MB 时保留最后 512KB，避免长期运行把私有目录撑爆。
        if (log.isFile && log.length() > 8L * 1024 * 1024) {
            runCatching {
                val bytes = log.readBytes()
                log.writeBytes(bytes.copyOfRange(bytes.size - 512 * 1024, bytes.size))
            }
        }
        log.appendText("\n===== ${java.time.OffsetDateTime.now()} 启动网关（端口 $port） =====\n")

        val pb = ProcessBuilder(binary.absolutePath, "-config", config.absolutePath)
        pb.directory(workDir())
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["SSL_CERT_FILE"] = ca.absolutePath
        env["HOME"] = workDir().absolutePath
        env["TMPDIR"] = app.cacheDir.absolutePath
        env["GODEBUG"] = "netdns=cgo"
        env["LANG"] = "zh_CN.UTF-8"
        env["TZ"] = runCatching { java.util.TimeZone.getDefault().id }.getOrDefault("UTC")

        val p = try {
            pb.start()
        } catch (e: Exception) {
            return GatewayStatus.Failed("无法启动网关进程：${e.message ?: e.javaClass.simpleName}")
        }
        process = p
        pumpLog(p, log)

        if (!waitHealthy(p, port)) {
            val tail = logTail(16)
            p.killCompat()
            process = null
            return GatewayStatus.Failed(
                "网关启动失败（端口 $port）" + if (tail.isBlank()) "" else "：\n$tail",
            )
        }

        runCatching { GatewayService.start(app) }
        return GatewayStatus.Running(port, key)
    }

    /** 停止网关进程（幂等）。 */
    fun stop() {
        val p = process
        process = null
        if (p != null) {
            p.killCompat()
        }
        runCatching { GatewayService.stop(app) }
        _status.value = GatewayStatus.Stopped
    }

    /** 重启：先停再起。 */
    suspend fun restart(): GatewayStatus {
        stop()
        Thread.sleep(350)
        return start()
    }

    /** 读取最近日志（用于界面展示与排障）。 */
    fun logTail(maxLines: Int = 120): String {
        val f = logFile()
        if (!f.isFile) return ""
        return runCatching {
            val len = f.length()
            val start = (len - 96 * 1024).coerceAtLeast(0)
            val bytes = ByteArray((len - start).toInt())
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                raf.readFully(bytes)
            }
            val text = String(bytes, Charsets.UTF_8)
            text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(maxLines).joinToString("\n")
        }.getOrDefault("")
    }



    /** 把子进程 stdout/stderr 持续写入日志文件（ProcessBuilder#redirectOutput 是 API 26，这里手写兼容）。 */
    private fun pumpLog(p: Process, log: File) {
        Thread({
            runCatching {
                java.io.FileOutputStream(log, true).use { out ->
                    p.inputStream.use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            out.flush()
                        }
                    }
                }
            }
        }, "wb-gateway-log").apply { isDaemon = true }.start()
    }
    /** API 24 兼容：Process#isAlive 是 API 26。 */
    private fun Process.aliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (e: IllegalThreadStateException) {
        true
    }

    /** API 24 兼容：Process#waitFor(timeout, unit) 是 API 26。 */
    private fun Process.waitForCompat(ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (!aliveCompat()) return true
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return !aliveCompat()
            }
        }
        return !aliveCompat()
    }

    /** 先 SIGTERM，3 秒不退再强杀（强杀 API 26+，低版本退化为多次 destroy）。 */
    private fun Process.killCompat() {
        runCatching { destroy() }
        if (waitForCompat(3_000)) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { destroyForcibly() }
        } else {
            runCatching { destroy() }
        }
        waitForCompat(2_000)
    }
    private fun binaryFile(): File = File(app.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun configFile(): File = File(workDir(), "config.json")

    private fun ensureConfig(): File {
        val f = configFile()
        if (f.isFile && f.length() > 2) return f
        val port = pickPort()
        val key = generateApiKey()
        val json = buildJsonObject {
            put("listen", "127.0.0.1:$port")
            put("api_key", key)
            put("auth_dir", "./auths")
            put("state_file", "./data/state.json")
            putJsonObject("panel") { put("package_detail_limit", 5) }
            putJsonObject("server") { put("read_timeout", "300s") }
            putJsonObject("logging") {
                put("request_archive_enabled", true)
                put("request_retention_days", 7)
                put("request_archive_max_mb", 100)
                put("request_client_info", true)
            }
            putJsonObject("schedule") {
                put("checkin_enabled", true)
                put("travel_enabled", true)
                put("activity_enabled", true)
                put("keepalive_enabled", true)
                put("growth_enabled", true)
                put("balance_refresh_enabled", true)
                put("balance_refresh_minutes", 5)
            }
        }
        f.writeText(json.toString())
        File(workDir(), "auths").mkdirs()
        File(workDir(), "data").mkdirs()
        return f
    }

    private fun readConfig(): JsonObject? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(configFile().readText()).jsonObject
    }.getOrNull()

    private fun readString(cfg: File, key: String): String? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(cfg.readText()).jsonObject[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun readPort(cfg: File): Int {
        val listen = readString(cfg, "listen") ?: return 0
        return listen.substringAfterLast(':').toIntOrNull() ?: 0
    }

    private fun pickPort(): Int {
        for (offset in 0 until PORT_TRIES) {
            val port = PORT_START + offset
            val ok = runCatching {
                ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).use { true }
            }.getOrDefault(false)
            if (ok) return port
        }
        return PORT_START
    }

    private fun generateApiKey(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return "sk-" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun ensureCaBundle(): File {
        val out = File(workDir(), "cacert.pem")
        if (!out.isFile || out.length() < 1024) {
            runCatching {
                app.assets.open("cacert.pem").use { input ->
                    out.outputStream().use { os -> input.copyTo(os) }
                }
            }
        }
        return out
    }


    /** 端口上跑的是不是本网关（/healthz 的 service 字段 = workbuddy2api）。 */
    private fun isOurGateway(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port$HEALTH_PATH").openConnection() as HttpURLConnection
        conn.connectTimeout = 600
        conn.readTimeout = 900
        conn.requestMethod = "GET"
        try {
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            body.contains("workbuddy2api")
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** 把配置里的 listen 改成新端口（保留其余字段）。 */
    private fun rewritePort(config: File, port: Int) {
        runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(config.readText()).jsonObject
            val updated = JsonObject(
                root + ("listen" to kotlinx.serialization.json.JsonPrimitive("127.0.0.1:$port")),
            )
            config.writeText(updated.toString())
        }
    }
    private fun waitHealthy(p: Process, port: Int): Boolean {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.aliveCompat()) return false
            if (ping(port)) return true
            Thread.sleep(300)
        }
        // 最后一次机会：慢设备首次启动可能要初始化状态文件
        return p.aliveCompat() && ping(port)
    }

    private fun ping(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port$HEALTH_PATH").openConnection() as HttpURLConnection
        conn.connectTimeout = 600
        conn.readTimeout = 900
        conn.requestMethod = "GET"
        try {
            // 只要拿到 HTTP 响应就说明进程已监听（账号池为空时 /healthz 也会返回 503）
            conn.responseCode > 0
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
