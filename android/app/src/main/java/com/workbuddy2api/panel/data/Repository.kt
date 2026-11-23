package com.workbuddy2api.panel.data

import kotlinx.serialization.json.JsonObject

/**
 * 账号凭证导出的结果。
 *
 * [content] 是网关原样返回的 JSON 文本（含明文 token），由调用方写入用户选择的文件；
 * [fileName] 优先取服务端 `Content-Disposition`，批量导出带时间戳。
 */
data class ExportResult(
    val fileName: String,
    val content: String,
    val exported: Int,
    val skipped: Int,
)

/**
 * 面板数据仓库：UI 只依赖这个接口。
 *
 * 远程实现 [RemoteWbRepository] 走 HTTP；[DemoWbRepository] 提供离线演示数据。
 * 所有方法失败时抛异常（[WbHttpException] 或 [java.io.IOException]），
 * message 已是可直接展示的中文。
 */
interface WbRepository {
    /** 展示用的服务器标识，如 `127.0.0.1:7863`。 */
    val serverLabel: String

    /** 是否演示数据。 */
    val isDemo: Boolean get() = false

    suspend fun overview(): Overview
    suspend fun logs(): List<LogEntry>
    suspend fun requestMetrics(): RequestMetrics
    suspend fun requestLogs(
        limit: Int = 200,
        outcome: String? = null,
        from: Long? = null,
        to: Long? = null,
    ): RequestLogsPage
    suspend fun models(): List<ModelInfo>

    suspend fun accountTasks(uid: String): AccountTasks
    suspend fun acceptTasks(uid: String, codes: List<String>): ActionResult
    suspend fun acceptAllTasks(uid: String): JsonObject
    suspend fun claimTask(uid: String, code: String): ActionResult
    suspend fun autoTask(uid: String, code: String): ActionResult
    suspend fun autoAllTasks(uid: String): JsonObject
    suspend fun scanAll(): ScanResult
    suspend fun runQueue(concurrency: Int = 2): QueueStartResult
    suspend fun taskQueue(): QueueStatus

    suspend fun schoolVouchers(): SchoolVouchersPage
    suspend fun packages(): List<PackageAccount>
    suspend fun usage(hours: Int? = null): UsageSnapshot
    suspend fun modelProbes(): ModelProbes

    suspend fun config(): ConfigEnvelope
    suspend fun saveConfig(patch: JsonObject): ConfigSaveResult

    suspend fun loginStart(realm: String): LoginStart
    suspend fun loginPoll(state: String): LoginPoll

    /**
     * 上传 cockpit tools 导出的账号数组 JSON，批量导入账号池。
     *
     * 与服务端 `POST /panel/api/import/cockpit` 一致：返回总数/成功/跳过数与逐条错误。
     */
    suspend fun importCockpit(fileName: String, content: ByteArray): ImportResult

    /**
     * 导出一个账号的凭证 JSON（与网关 `auths/workbuddy-*.json` 同构，可直接放回 auths/ 目录）。
     *
     * 与服务端 `GET /panel/api/accounts/{uid}/export` 一致：**内容含 accessToken /
     * refreshToken 明文**，只能落进用户自己选的文件，不要写日志、不要缓存。
     */
    suspend fun exportAccount(uid: String): ExportResult

    /**
     * 批量导出全部账号凭证为单个 JSON 数组（元素与单账号导出一致）。
     *
     * 与服务端 `GET /panel/api/accounts/export` 一致；凭证为空等异常账号计入 skipped。
     */
    suspend fun exportAll(): ExportResult

    suspend fun accountAction(uid: String, action: AccountAction): ActionResult
    suspend fun bulkAction(action: BulkAction): ActionResult

    /** 释放底层连接。 */
    fun close() {}
}
