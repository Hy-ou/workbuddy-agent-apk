package com.workbuddy2api.panel.dsh

import android.content.Context
import com.workbuddy2api.panel.util.NotificationCenter
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 内核 ↔ 宿主 的最小回环桥（127.0.0.1，随机 token）。
 *
 * 供 DSH 插件调用宿主能力：目前提供 `POST /notify`（发通知），
 * 后续可扩展 /shell（Shizuku）等。只监听回环、要求 token，不接受外部访问。
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

    private fun handle(context: Context, token: String, client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            var auth = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                if (lower.startsWith("x-wb-token:")) auth = line.substringAfter(':').trim()
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read)
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
                path == "/health" -> 200 to """{"ok":true}"""
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
