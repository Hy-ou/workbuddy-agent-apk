package com.workbuddy2api.panel.data

import kotlinx.serialization.json.JsonObject

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

    suspend fun accountAction(uid: String, action: AccountAction): ActionResult
    suspend fun bulkAction(action: BulkAction): ActionResult

    /** 释放底层连接。 */
    fun close() {}
}
