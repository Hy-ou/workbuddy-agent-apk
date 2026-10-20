package com.workbuddy2api.panel.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** 通过 HTTP 访问面板 API 的仓库实现。 */
class RemoteWbRepository(serverUrl: String, apiKey: String) : WbRepository {
    private val api = WbApi(serverUrl, apiKey)

    override val serverLabel: String =
        normalizeServerUrl(serverUrl).removePrefix("https://").removePrefix("http://")

    private suspend inline fun <reified T> getJson(path: String, timeout: Long = 60): T =
        WbJson.decodeFromString(api.get(path, timeout))

    private suspend inline fun <reified T> postJson(path: String, body: String? = null, timeout: Long = 60): T =
        WbJson.decodeFromString(api.post(path, body, timeout))

    override suspend fun overview(): Overview = getJson("overview")

    override suspend fun logs(): List<LogEntry> = getJson<LogsPage>("logs").entries

    override suspend fun requestMetrics(): RequestMetrics = getJson("request_metrics")

    override suspend fun requestLogs(limit: Int, outcome: String?, from: Long?, to: Long?): RequestLogsPage {
        val q = buildList {
            add("limit=$limit")
            outcome?.takeIf { it.isNotBlank() }?.let { add("outcome=" + enc(it)) }
            from?.let { add("from=$it") }
            to?.let { add("to=$it") }
        }.joinToString("&")
        return getJson("request_logs?$q")
    }

    override suspend fun models(): List<ModelInfo> =
        getJson<ModelsPage>("models", timeout = 120).models ?: emptyList()

    override suspend fun accountTasks(uid: String): AccountTasks =
        getJson("accounts/${enc(uid)}/tasks", timeout = 120)

    override suspend fun acceptTasks(uid: String, codes: List<String>): ActionResult {
        val body = buildJsonObject {
            put("task_codes", JsonArray(codes.map { JsonPrimitive(it) }))
        }.toString()
        return postJson("accounts/${enc(uid)}/tasks/accept", body, timeout = 120)
    }

    override suspend fun acceptAllTasks(uid: String): JsonObject =
        parseObject(api.post("accounts/${enc(uid)}/tasks/accept_all", null, 180))

    override suspend fun claimTask(uid: String, code: String): ActionResult {
        val body = buildJsonObject { put("task_code", code) }.toString()
        return postJson("accounts/${enc(uid)}/tasks/claim", body, timeout = 120)
    }

    override suspend fun autoTask(uid: String, code: String): ActionResult {
        val body = buildJsonObject { put("task_code", code) }.toString()
        return postJson("accounts/${enc(uid)}/tasks/auto", body, timeout = 300)
    }

    override suspend fun autoAllTasks(uid: String): JsonObject =
        parseObject(api.post("accounts/${enc(uid)}/tasks/auto_all", null, 330))

    override suspend fun scanAll(): ScanResult = postJson("tasks/scan_all", null, timeout = 180)

    override suspend fun runQueue(concurrency: Int): QueueStartResult {
        val body = buildJsonObject { put("concurrency", concurrency.coerceIn(1, 4)) }.toString()
        return postJson("tasks/run_queue", body, timeout = 120)
    }

    override suspend fun taskQueue(): QueueStatus = getJson("tasks/queue")

    override suspend fun schoolVouchers(): SchoolVouchersPage =
        getJson("school/vouchers", timeout = 120)

    override suspend fun packages(): List<PackageAccount> =
        getJson<PackagesPage>("packages", timeout = 180).accounts

    override suspend fun usage(hours: Int?): UsageSnapshot {
        val path = if (hours != null) "usage?hours=$hours" else "usage"
        return getJson(path)
    }

    override suspend fun modelProbes(): ModelProbes = getJson("model_probes")

    override suspend fun config(): ConfigEnvelope = getJson("config")

    override suspend fun saveConfig(patch: JsonObject): ConfigSaveResult =
        postJson("config", patch.toString(), timeout = 60)

    override suspend fun loginStart(realm: String): LoginStart {
        val body = buildJsonObject { put("realm", if (realm == "global") "global" else "cn") }.toString()
        return postJson("login/start", body, timeout = 60)
    }

    override suspend fun loginPoll(state: String): LoginPoll =
        getJson("login/poll?state=" + enc(state), timeout = 60)

    override suspend fun accountAction(uid: String, action: AccountAction): ActionResult =
        postJson("accounts/${enc(uid)}/${action.path}", null, timeout = 120)

    override suspend fun bulkAction(action: BulkAction): ActionResult =
        postJson(action.path, null, timeout = if (action.async) 30 else 180)

    override fun close() = api.close()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parseObject(text: String): JsonObject =
        runCatching { WbJson.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}
