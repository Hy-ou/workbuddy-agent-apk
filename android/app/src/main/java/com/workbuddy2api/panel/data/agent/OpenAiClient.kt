package com.workbuddy2api.panel.data.agent

import com.workbuddy2api.panel.data.WbHttpException
import com.workbuddy2api.panel.data.WbJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容接口的极简客户端（Agent 专用）。
 *
 * 只做两件事：
 * 1. `GET {base}/v1/models` —— 取模型列表（兼容 `data[].id`、`models[]`、纯字符串数组等形状）；
 * 2. `POST {base}/v1/chat/completions`（`stream=true`）—— 用 SSE 逐块吐出增量文本。
 *
 * 设计要点：
 * - 专供本地网关 / 远程服务使用，不依赖面板 API 的 `WbApi`（两者的超时策略完全不同）；
 * - 流式请求 `readTimeout = 0`（长回答可能中途长时间没有新块），并单独关闭「整调用超时」，
 *   否则默认的 120s callTimeout 会掐断长回答；[close] 与取消收集都会 cancel 掉在途请求；
 * - 解析按「行」缓冲（Okio 的 readUtf8Line 会正确处理跨 chunk 的半行与 `\r\n`），
 *   `data: [DONE]` 结束；`reasoning_content` 等思考过程一律忽略，只 emit `delta.content`；
 * - 任何失败都抛异常，且 message 已是可直接展示的中文（HTTP 错误用 [WbHttpException]，
 *   网络/超时用 [IOException]），UI 直接显示 `e.message` 即可。
 */
class OpenAiClient(baseUrl: String, apiKey: String) : Closeable {

    companion object {
        /** 普通请求（模型列表）的整调用超时；流式请求会单独关掉它。 */
        private const val CALL_TIMEOUT_SEC = 120L

        /** 模型列表属于短请求，单独收紧，避免网关卡住时界面干等。 */
        private const val MODELS_TIMEOUT_SEC = 30L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 规范化服务地址：补协议、去尾部斜杠、容忍误加的 `/v1`（避免拼出 `/v1/v1/models`）。 */
        private fun normalizeBase(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.endsWith("/v1")) {
                s = s.dropLast(3)
                while (s.endsWith("/")) s = s.dropLast(1)
            }
            return s
        }
    }

    private val base = normalizeBase(baseUrl)
    private val key = apiKey.trim()

    /** 独立连接池：网关重启 / 长时间流式互不影响面板请求。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 0 = 不限制：SSE 长连接期间可能几十秒没有新字节，不能按普通请求算读超时
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 拉取可用模型列表。
     *
     * 解析顺序：`data[]` → `models[]` → `result[]` → 根数组；数组元素支持
     * 字符串（`"global:deepseek-v4.1-flash"`）或对象（取 `id` / `model` / `name`）。
     * 无法识别的响应返回空列表（界面回退成手动输入模型名），不抛异常。
     *
     * @throws WbHttpException 非 2xx（message 为中文，如「API Key 无效」）
     * @throws IOException 网络不可达 / 超时 / 地址非法（message 为中文）
     */
    suspend fun models(): List<ModelOption> = withContext(Dispatchers.IO) {
        val target = endpoint("/v1/models")
        val request = Request.Builder()
            .url(target)
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
            .header("Accept", "application/json")
            .get()
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(MODELS_TIMEOUT_SEC, TimeUnit.SECONDS)
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw httpError(response.code, text)
                parseModels(text)
            }
        } catch (e: WbHttpException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw IOException("获取模型列表超时，请检查网关是否已启动或更换服务地址", e)
        } catch (e: IOException) {
            throw IOException("无法获取模型列表：${e.message ?: "网络错误"}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("服务地址无效：$target", e)
        }
    }

    /**
     * 流式对话。
     *
     * 冷流：只有 collect 时才会真正发请求；取消收集（界面「停止」/ 协程销毁）
     * 会立即 cancel 掉在途 HTTP 调用并关闭连接。
     *
     * 请求体：`{"model":..., "stream":true, "messages":[{"role":"system",...}] + messages}`。
     * system 提示词为空时不发 system 消息；`content` 为空的消息会被跳过；
     * `role="tool"` 在协议层映射成 `user`（前缀「工具执行结果：」），
     * 因为多数上游不接受没有 `tool_call_id` 的裸 tool 角色。
     *
     * 每收到一块增量文本 emit 一次；`data: [DONE]` 结束；`reasoning_content` 被忽略。
     *
     * @throws WbHttpException 非 2xx（message 为中文）
     * @throws IOException 网络中断 / 服务端流内返回 error
     */
    fun chatStream(
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
    ): Flow<String> = channelFlow<String> {
        val target = endpoint("/v1/chat/completions")
        val body = buildChatBody(model, messages, systemPrompt)
        val request = Request.Builder()
            .url(target)
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val call = client.newCall(request)
        // 流式回答的时长由用户决定：把「整调用超时」清掉并把截止时间推远，
        // 否则客户端上 120s 的 callTimeout 会掐断长回答；停止由取消收集 / close() 触发。
        call.timeout().timeout(0, TimeUnit.SECONDS).deadline(12, TimeUnit.HOURS)

        val out = channel
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw httpError(response.code, response.body?.string().orEmpty())
                    }
                    val responseBody = response.body ?: throw IOException("服务端未返回数据流")
                    val source = responseBody.source()
                    while (true) {
                        // readUtf8Line 自动处理 \r\n 与跨 chunk 的半行
                        val raw = source.readUtf8Line() ?: break
                        val line = raw.trim()
                        if (line.isEmpty() || !line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        val delta = extractDelta(data)
                        if (delta.isNotEmpty()) out.send(delta)
                    }
                }
                out.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 异常交给收集端（界面显示错误气泡），不吞掉
                out.close(e)
            }
        }
        // 下游取消 / 正常结束：都要把 HTTP 调用关掉，避免线程与连接泄漏
        awaitClose { call.cancel() }
    }

    /** 释放连接池（在途请求会被 cancel）。 */
    override fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    private fun endpoint(path: String): String {
        if (base.isBlank()) throw IOException("服务地址为空：请先启动本地网关或连接远程服务")
        return base + path
    }

    private fun buildChatBody(model: String, messages: List<ChatMessage>, systemPrompt: String): String =
        buildJsonObject {
            put("model", model)
            put("stream", true)
            put(
                "messages",
                buildJsonArray {
                    val system = systemPrompt.trim()
                    if (system.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", system)
                            },
                        )
                    }
                    messages.forEach { message ->
                        val content = message.content.trim()
                        if (content.isEmpty()) return@forEach
                        val role = when (message.role.lowercase()) {
                            "system", "assistant" -> message.role.lowercase()
                            "tool" -> "user"
                            else -> "user"
                        }
                        val text = if (message.role.lowercase() == "tool") "工具执行结果：$content" else content
                        add(
                            buildJsonObject {
                                put("role", role)
                                put("content", text)
                            },
                        )
                    }
                },
            )
        }.toString()

    /** 从一行 SSE 的 JSON 里取增量文本；兼容 `delta.content` / `message.content` / `text`。 */
    private fun extractDelta(data: String): String {
        val root = runCatching { WbJson.parseToJsonElement(data) }.getOrNull()
        if (root !is JsonObject) return ""
        // 流内错误块：直接抛，让界面提示
        val error = root["error"]
        if (error is JsonObject) {
            val msg = error.stringOrNull("message") ?: error.stringOrNull("code")
            throw IOException("服务端返回错误：${msg ?: "未知错误"}")
        }
        if (error is JsonPrimitive) {
            val msg = error.contentOrNull
            if (!msg.isNullOrBlank()) throw IOException("服务端返回错误：$msg")
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        val content = delta?.stringOrNull("content")
        if (!content.isNullOrEmpty()) return content
        // 个别实现在非流式兜底时把文本放在 message 里
        val message = (choice?.get("message") as? JsonObject)?.stringOrNull("content")
        if (!message.isNullOrEmpty()) return message
        // 兼容 completion 风格与「只有 delta 文本」的简化实现
        val text = choice?.stringOrNull("text") ?: root.stringOrNull("content")
        return text.orEmpty()
    }

    private fun parseModels(text: String): List<ModelOption> {
        val root = runCatching { WbJson.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray)
                ?: (root["models"] as? JsonArray)
                ?: (root["result"] as? JsonArray)
                ?: (root["items"] as? JsonArray)
            else -> null
        } ?: return emptyList()
        val out = LinkedHashMap<String, ModelOption>()
        for (element in array) {
            when (element) {
                is JsonObject -> {
                    val id = element.stringOrNull("id")
                        ?: element.stringOrNull("model")
                        ?: element.stringOrNull("name")
                        ?: continue
                    val label = element.stringOrNull("label")
                        ?: element.stringOrNull("display_name")
                        ?: element.stringOrNull("title")
                        ?: id
                    out[id] = ModelOption(id, label)
                }
                is JsonPrimitive -> {
                    val id = element.contentOrNull?.trim().orEmpty()
                    if (id.isNotEmpty()) out[id] = ModelOption(id, id)
                }
                else -> Unit
            }
        }
        return out.values.toList()
    }

    /** 取对象的字符串字段：只认真正的字符串（数字/布尔的 content 不当作模型名）。 */
    private fun JsonObject.stringOrNull(name: String): String? {
        val value = this[name] as? JsonPrimitive ?: return null
        val text = value.contentOrNull?.trim().orEmpty()
        return text.ifEmpty { null }
    }

    /** 非 2xx 统一转成带中文提示的 [WbHttpException]。 */
    private fun httpError(code: Int, body: String): Exception = when (code) {
        401, 403 -> WbHttpException(code, body, "API Key 无效或已失效（HTTP $code）")
        404 -> WbHttpException(code, body, "接口不存在（HTTP 404）：请确认地址指向网关或兼容服务")
        429 -> WbHttpException(code, body, "请求过于频繁或额度不足，请稍后重试（HTTP 429）")
        in 500..599 -> WbHttpException(code, body, "服务端异常（HTTP $code）${serverMessage(body)}")
        else -> WbHttpException(code, body, "请求失败（HTTP $code）${serverMessage(body)}")
    }

    /** 从错误响应体里抠出服务端 message，附在中文提示后面（最多 200 字）。 */
    private fun serverMessage(body: String): String {
        if (body.isBlank()) return ""
        val msg = runCatching {
            val obj = WbJson.parseToJsonElement(body).jsonObject
            val nested = (obj["error"] as? JsonObject)?.stringOrNull("message")
                ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
                ?: obj.stringOrNull("message")
                ?: obj.stringOrNull("error")
            nested?.trim()
        }.getOrNull()
        return if (msg.isNullOrBlank()) "" else "：${msg.take(200)}"
    }
}
