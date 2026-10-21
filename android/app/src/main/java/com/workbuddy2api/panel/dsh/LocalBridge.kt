package com.workbuddy2api.panel.dsh

import android.content.Context
import com.workbuddy2api.panel.util.NotificationCenter
import com.workbuddy2api.panel.util.ShizukuShell
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 内核 ↔ 宿主 的最小回环桥（127.0.0.1，随机 token）。
 *
 * 供 DSH 插件调用宿主能力：
 *  - `POST /notify`：发系统通知；
 *  - `POST /shell`：以 Shizuku（uid 2000）执行系统命令 —— 内核里的 `wb_shizuku` 工具走这里；
 *  - `POST /health`：桥与 Shizuku 状态（供 `wb_env` 体检）。
 * 只监听回环、要求 token，不接受外部访问。
 */
object LocalBridge {

    const val PORT = 7865
    private const val PREFS = "wb_bridge"
    private const val KEY_TOKEN = "token"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    fun token(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = p.getString(KEY_TOKEN, null)
        if (!old.isNullOrBlank()) return old
        val t = java.util.UUID.randomUUID().toString().replace("-", "")
        p.edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    /** 幂等启动；失败（端口占用等）时静默返回 false，不影响内核。 */
    @Synchronized
    fun start(context: Context): Boolean {
        if (server != null) return true
        return runCatching {
            val s = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            server = s
            val app = context.applicationContext
            val tok = token(app)
            thread = Thread({
                while (!s.isClosed) {
                    val client = runCatching { s.accept() }.getOrNull() ?: break
                    runCatching { handle(app, tok, client) }
                }
            }, "wb-bridge").apply { isDaemon = true; start() }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
    }

    /**
     * `POST /shell`：{ command, timeoutMs } -> { ok, code, output }。
     * 未安装/未授权 Shizuku 时返回 409 + 中文原因（工具侧转成可读失败，不抛异常）。
     */
    private fun shell(body: String): Pair<Int, String> {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val command = json?.optString("command").orEmpty()
        if (command.isBlank()) return 400 to JSONObject().put("ok", false).put("error", "command required").toString()
        val timeoutMs = (json?.optInt("timeoutMs") ?: 25_000).coerceIn(1_000, 120_000).toLong()
        val result = runCatching {
            kotlinx.coroutines.runBlocking { ShizukuShell.exec(command, timeoutMs) }
        }
        val shellResult = result.getOrNull()
            ?: return 409 to JSONObject()
                .put("ok", false)
                .put("error", result.exceptionOrNull()?.message ?: "Shizuku 不可用")
                .toString()
        return 200 to JSONObject()
            .put("ok", true)
            .put("code", shellResult.code)
            .put("output", shellResult.output.take(64 * 1024))
            .toString()
    }

    private fun handle(context: Context, token: String, client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5000
            val input = sock.getInputStream()

            // 头部与正文都按【字节】读：Content-Length 是字节数，按字符读会在 UTF-8 中文正文上
            // 少读字节而一直等（客户端表现为 fetch failed / 超时）。BufferedReader 也不可用，
            // 它会把正文一起缓冲进字符流里。
            val header = java.io.ByteArrayOutputStream()
            val tail = byteArrayOf(13, 10, 13, 10)   // \r\n\r\n
            var matched = 0
            while (header.size() < 16 * 1024) {
                val b = input.read()
                if (b < 0) break
                header.write(b)
                matched = when {
                    b.toByte() == tail[matched] -> matched + 1
                    b.toByte() == tail[0] -> 1
                    else -> 0
                }
                if (matched == tail.size) break
            }
            val lines = String(header.toByteArray(), Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
            val requestLine = lines.firstOrNull() ?: return
            var contentLength = 0
            var auth = ""
            for (line in lines.drop(1)) {
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = (line.substringAfter(':').trim().toIntOrNull() ?: 0).coerceIn(0, 1024 * 1024)
                }
                if (lower.startsWith("x-wb-token:")) auth = line.substringAfter(':').trim()
            }
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            } else ""

            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1).orEmpty()
            val method = parts.getOrNull(0).orEmpty()

            val (code, payload) = when {
                method != "POST" -> 405 to """{"ok":false,"error":"method not allowed"}"""
                auth != token -> 401 to """{"ok":false,"error":"bad token"}"""
                path == "/notify" -> {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val title = json?.optString("title")?.ifBlank { "WorkBuddy" } ?: "WorkBuddy"
                    val text = json?.optString("text") ?: ""
                    if (text.isBlank()) {
                        400 to """{"ok":false,"error":"text required"}"""
                    } else if (NotificationCenter.post(context, title, text)) {
                        200 to """{"ok":true}"""
                    } else {
                        500 to """{"ok":false,"error":"notification failed"}"""
                    }
                }
                path == "/shell" -> shell(body)
                path == "/health" -> {
                    val available = runCatching { ShizukuShell.available() }.getOrDefault(false)
                    val granted = runCatching { ShizukuShell.granted() }.getOrDefault(false)
                    200 to JSONObject()
                        .put("ok", true)
                        .put("shizuku", JSONObject().put("available", available).put("granted", granted))
                        .toString()
                }
                else -> 404 to """{"ok":false,"error":"not found"}"""
            }
            val bytes = payload.toByteArray()
            val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }.use { }
        }
    }
}
