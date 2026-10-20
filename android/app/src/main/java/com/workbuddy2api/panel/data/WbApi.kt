package com.workbuddy2api.panel.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** 带 HTTP 状态的异常：message 已是可直接展示的中文。 */
class WbHttpException(val status: Int, val body: String, override val message: String) : Exception(message)

/**
 * 极简 OkHttp 客户端：一个实例对应一个服务器连接。
 *
 * 服务端响应分三种信封（裸对象 / {ok:true,...} / {ok:false,error}），
 * 这里只负责搬运字符串，解析交给各 Repository 方法。
 */
class WbApi(baseUrl: String, private val apiKey: String) : Closeable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val root = apiBase(baseUrl)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun get(path: String, timeoutSec: Long = 60): String = execute(path, null, timeoutSec, post = false)

    suspend fun post(path: String, json: String? = null, timeoutSec: Long = 60): String =
        execute(path, json, timeoutSec, post = true)

    private suspend fun execute(path: String, body: String?, timeoutSec: Long, post: Boolean): String =
        withContext(Dispatchers.IO) {
            val url = if (path.startsWith("http://") || path.startsWith("https://")) path
            else root.trimEnd('/') + "/" + path.trimStart('/')
            val builder = Request.Builder().url(url)
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            if (post) builder.post((body ?: "").toRequestBody(jsonMedia)) else builder.get()
            val call = client.newCall(builder.build())
            if (timeoutSec > 0 && timeoutSec != 60L) {
                call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
            }
            try {
                call.execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        when (resp.code) {
                            401 -> throw WbHttpException(401, text, "API Key 无效或已变更")
                            else -> throw WbHttpException(
                                resp.code,
                                text,
                                parseErrorMessage(text) ?: "请求失败（HTTP ${resp.code}）",
                            )
                        }
                    }
                    text
                }
            } catch (e: WbHttpException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw IOException("连接超时，请检查服务器地址与网络", e)
            } catch (e: IOException) {
                throw IOException("无法连接服务器，请检查网络与地址", e)
            }
        }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun parseErrorMessage(body: String): String? = try {
        val obj = Json.parseToJsonElement(body).jsonObject
        obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/** 规范化用户输入的服务器地址：补 http://、去尾部斜杠、容忍误加的 /panel 路径。 */
fun normalizeServerUrl(input: String): String {
    var s = input.trim()
    if (s.isEmpty()) return ""
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    s = s.trimEnd('/')
    if (s.endsWith("/panel/api")) s = s.removeSuffix("/panel/api")
    else if (s.endsWith("/panel")) s = s.removeSuffix("/panel")
    return s.trimEnd('/')
}

/** 面板 API 根：`<server>/panel/api`。 */
fun apiBase(url: String): String = normalizeServerUrl(url).trimEnd('/') + "/panel/api"
